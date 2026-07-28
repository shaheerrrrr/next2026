#include <stdio.h>
#include <string.h>

#include "hardware/gpio.h"
#include "hardware/i2c.h"
#include "hardware/irq.h"
#include "hardware/sync.h"
#include "hardware/uart.h"
#include "pico/i2c_slave.h"
#include "pico/multicore.h"
#include "pico/stdlib.h"

#define I2C_PORT i2c0
#define I2C_ADDRESS 0x42
#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5
#define I2C_BAUDRATE 100000

#define UART_PORT uart0
#define UART_TX_PIN 0
#define UART_RX_PIN 1
#define UART_BAUDRATE 115200

#define SNAPSHOT_LEN 56
#define PROTOCOL_VERSION 1

// Second, independent I2C slave served entirely by core1. It has its own bus,
// its own address, and its own buffers so that no byte is ever written by one
// core and read by the other. save_and_disable_interrupts() only touches the
// executing core's PRIMASK, so it gives no cross-core protection; strict
// per-core buffer ownership is what makes both slaves safe.
#define ULTRASONIC_I2C_PORT i2c1
#define ULTRASONIC_I2C_ADDRESS 0x43
#define ULTRASONIC_I2C_SDA_PIN 6
#define ULTRASONIC_I2C_SCL_PIN 7
#define ULTRASONIC_I2C_BAUDRATE 100000

#define ULTRASONIC_TRIGGER_PIN 14
#define ULTRASONIC_ECHO_PIN 15

#define ULTRASONIC_LEN 12
#define ULTRASONIC_DISTANCE_INVALID 0xffffu
#define ULTRASONIC_MIN_VALID_MM 20u
#define ULTRASONIC_MAX_VALID_MM 4000u

// A genuine HC-SR04 holds ECHO high for about 38 ms to report "no object", and
// clones can hold it far longer. The falling-edge timeout must therefore stay
// above 40 ms, and every cycle must confirm ECHO is idle before triggering, or
// the sensor desynchronizes and appears permanently stuck.
#define ULTRASONIC_ECHO_IDLE_TIMEOUT_US 5000u
#define ULTRASONIC_ECHO_RISE_TIMEOUT_US 15000u
#define ULTRASONIC_ECHO_HIGH_TIMEOUT_US 45000u
#define ULTRASONIC_CYCLE_MS 60

static const uint8_t SNAPSHOT_MAGIC[4] = {'F', 'N', 'A', 'V'};
static const uint8_t ULTRASONIC_MAGIC[4] = {'F', 'S', 'O', 'N'};

// The foreground UART parser publishes here. The I2C ISR copies this into
// latched_snapshot when the Control Hub selects register 0x00.
static uint8_t published_snapshot[SNAPSHOT_LEN];
static uint8_t latched_snapshot[SNAPSHOT_LEN];

static struct {
    uint8_t register_address;
    bool register_written;
} i2c_context;

// Core1 owns everything below. The ultrasonic slave keeps its own context so
// the two I2C interrupt handlers, which run on different cores, never share
// register_address or register_written state.
static uint8_t published_ultrasonic[ULTRASONIC_LEN];
static uint8_t latched_ultrasonic[ULTRASONIC_LEN];

static struct {
    uint8_t register_address;
    bool register_written;
} ultrasonic_i2c_context;

static uint8_t uart_frame[SNAPSHOT_LEN];
static size_t uart_frame_index;
static uint32_t accepted_frames;
static uint32_t rejected_frames;
static volatile uint32_t i2c_transactions;

static uint16_t crc16_ccitt_false(const uint8_t *data, size_t length) {
    uint16_t crc = 0xffff;
    for (size_t i = 0; i < length; ++i) {
        crc ^= (uint16_t)data[i] << 8;
        for (uint8_t bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021)
                                 : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

static void put_u16_le(uint8_t *buffer, size_t offset, uint16_t value) {
    buffer[offset] = (uint8_t)value;
    buffer[offset + 1] = (uint8_t)(value >> 8);
}

static void put_u32_le(uint8_t *buffer, size_t offset, uint32_t value) {
    buffer[offset] = (uint8_t)value;
    buffer[offset + 1] = (uint8_t)(value >> 8);
    buffer[offset + 2] = (uint8_t)(value >> 16);
    buffer[offset + 3] = (uint8_t)(value >> 24);
}

static void build_startup_snapshot(void) {
    memset(published_snapshot, 0, sizeof(published_snapshot));
    memcpy(published_snapshot, SNAPSHOT_MAGIC, sizeof(SNAPSHOT_MAGIC));
    published_snapshot[4] = PROTOCOL_VERSION;
    published_snapshot[5] = SNAPSHOT_LEN;
    put_u32_le(published_snapshot, 32, UINT32_MAX);
    put_u32_le(published_snapshot, 36, UINT32_MAX);
    put_u16_le(
            published_snapshot,
            SNAPSHOT_LEN - 2,
            crc16_ccitt_false(published_snapshot, SNAPSHOT_LEN - 2));
    memcpy(latched_snapshot, published_snapshot, sizeof(latched_snapshot));
}

static bool snapshot_is_valid(const uint8_t *frame) {
    if (memcmp(frame, SNAPSHOT_MAGIC, sizeof(SNAPSHOT_MAGIC)) != 0) return false;
    if (frame[4] != PROTOCOL_VERSION || frame[5] != SNAPSHOT_LEN) return false;

    uint16_t encoded_crc =
            (uint16_t)frame[SNAPSHOT_LEN - 2]
            | ((uint16_t)frame[SNAPSHOT_LEN - 1] << 8);
    return encoded_crc == crc16_ccitt_false(frame, SNAPSHOT_LEN - 2);
}

static void publish_snapshot(const uint8_t *frame) {
    // The I2C handler is the only interrupt touching these bytes. Blocking it
    // for this short copy prevents a torn snapshot.
    uint32_t interrupt_state = save_and_disable_interrupts();
    memcpy(published_snapshot, frame, SNAPSHOT_LEN);
    restore_interrupts(interrupt_state);
}

static void reset_uart_parser_with(uint8_t byte) {
    uart_frame_index = 0;
    if (byte == SNAPSHOT_MAGIC[0]) {
        uart_frame[0] = byte;
        uart_frame_index = 1;
    }
}

static void consume_uart_byte(uint8_t byte) {
    if (uart_frame_index < sizeof(SNAPSHOT_MAGIC)) {
        if (byte == SNAPSHOT_MAGIC[uart_frame_index]) {
            uart_frame[uart_frame_index++] = byte;
        } else {
            reset_uart_parser_with(byte);
        }
        return;
    }

    uart_frame[uart_frame_index++] = byte;

    // Reject an impossible header immediately and resume scanning the stream.
    if (uart_frame_index == 6
            && (uart_frame[4] != PROTOCOL_VERSION
                || uart_frame[5] != SNAPSHOT_LEN)) {
        rejected_frames++;
        reset_uart_parser_with(byte);
        return;
    }

    if (uart_frame_index != SNAPSHOT_LEN) return;

    if (snapshot_is_valid(uart_frame)) {
        publish_snapshot(uart_frame);
        accepted_frames++;
    } else {
        rejected_frames++;
    }
    uart_frame_index = 0;
}

static void i2c_slave_handler(i2c_inst_t *i2c, i2c_slave_event_t event) {
    switch (event) {
        case I2C_SLAVE_RECEIVE: {
            uint8_t byte = i2c_read_byte_raw(i2c);
            if (!i2c_context.register_written) {
                i2c_context.register_address = byte;
                i2c_context.register_written = true;

                // A read beginning at zero starts a four-chunk Control Hub poll.
                // Latch once so all chunks have one matching CRC.
                if (byte == 0) {
                    memcpy(latched_snapshot, published_snapshot, SNAPSHOT_LEN);
                }
            }
            break;
        }

        case I2C_SLAVE_REQUEST: {
            uint8_t value = 0;
            if (i2c_context.register_address < SNAPSHOT_LEN) {
                value = latched_snapshot[i2c_context.register_address];
            }
            i2c_write_byte_raw(i2c, value);
            i2c_context.register_address++;
            break;
        }

        case I2C_SLAVE_FINISH:
            i2c_context.register_written = false;
            i2c_transactions++;
            break;

        default:
            break;
    }
}

static void fill_ultrasonic_frame(
        uint8_t *frame, uint16_t distance_mm, uint16_t sample_seq) {
    memset(frame, 0, ULTRASONIC_LEN);
    memcpy(frame, ULTRASONIC_MAGIC, sizeof(ULTRASONIC_MAGIC));
    frame[4] = PROTOCOL_VERSION;
    frame[5] = ULTRASONIC_LEN;
    put_u16_le(frame, 6, distance_mm);
    put_u16_le(frame, 8, sample_seq);
    put_u16_le(
            frame,
            ULTRASONIC_LEN - 2,
            crc16_ccitt_false(frame, ULTRASONIC_LEN - 2));
}

static void build_startup_ultrasonic(void) {
    // Runs before the i2c1 slave answers its address, so no critical section is
    // needed. Both buffers are seeded because a Control Hub poll can arrive
    // microseconds after the slave goes live.
    fill_ultrasonic_frame(published_ultrasonic, ULTRASONIC_DISTANCE_INVALID, 0);
    memcpy(latched_ultrasonic, published_ultrasonic, sizeof(latched_ultrasonic));
}

static void publish_ultrasonic(uint16_t distance_mm, uint16_t sample_seq) {
    uint8_t frame[ULTRASONIC_LEN];
    fill_ultrasonic_frame(frame, distance_mm, sample_seq);

    // Only the copy is guarded. Masking interrupts across the trigger pulse or
    // the echo wait would stall the i2c1 handler on this core for tens of
    // milliseconds, which is exactly what running on core1 exists to avoid.
    uint32_t interrupt_state = save_and_disable_interrupts();
    memcpy(published_ultrasonic, frame, ULTRASONIC_LEN);
    restore_interrupts(interrupt_state);
}

static uint16_t read_ultrasonic_distance_mm(void) {
    // Never trigger while the previous echo pulse is still asserted.
    absolute_time_t idle_deadline =
            make_timeout_time_us(ULTRASONIC_ECHO_IDLE_TIMEOUT_US);
    while (gpio_get(ULTRASONIC_ECHO_PIN)) {
        if (time_reached(idle_deadline)) return ULTRASONIC_DISTANCE_INVALID;
        tight_loop_contents();
    }

    gpio_put(ULTRASONIC_TRIGGER_PIN, 1);
    busy_wait_us_32(10);
    gpio_put(ULTRASONIC_TRIGGER_PIN, 0);

    absolute_time_t rise_deadline =
            make_timeout_time_us(ULTRASONIC_ECHO_RISE_TIMEOUT_US);
    while (!gpio_get(ULTRASONIC_ECHO_PIN)) {
        if (time_reached(rise_deadline)) return ULTRASONIC_DISTANCE_INVALID;
        tight_loop_contents();
    }

    // Timestamp the real edges. Counting loop iterations would be corrupted by
    // however long an I2C interrupt preempts this loop.
    uint32_t echo_start_us = time_us_32();

    absolute_time_t fall_deadline =
            make_timeout_time_us(ULTRASONIC_ECHO_HIGH_TIMEOUT_US);
    while (gpio_get(ULTRASONIC_ECHO_PIN)) {
        if (time_reached(fall_deadline)) return ULTRASONIC_DISTANCE_INVALID;
        tight_loop_contents();
    }

    uint32_t pulse_width_us = time_us_32() - echo_start_us;
    uint32_t distance_mm = (pulse_width_us * 10u) / 58u;
    if (distance_mm < ULTRASONIC_MIN_VALID_MM
            || distance_mm > ULTRASONIC_MAX_VALID_MM) {
        return ULTRASONIC_DISTANCE_INVALID;
    }
    return (uint16_t)distance_mm;
}

static void ultrasonic_i2c_handler(i2c_inst_t *i2c, i2c_slave_event_t event) {
    switch (event) {
        case I2C_SLAVE_RECEIVE: {
            uint8_t byte = i2c_read_byte_raw(i2c);
            if (!ultrasonic_i2c_context.register_written) {
                ultrasonic_i2c_context.register_address = byte;
                ultrasonic_i2c_context.register_written = true;

                // Latch once at register zero so a multi-byte read cannot mix
                // bytes from two ping cycles and fail CRC on the host side.
                if (byte == 0) {
                    memcpy(latched_ultrasonic,
                           published_ultrasonic,
                           ULTRASONIC_LEN);
                }
            }
            break;
        }

        case I2C_SLAVE_REQUEST: {
            uint8_t value = 0;
            if (ultrasonic_i2c_context.register_address < ULTRASONIC_LEN) {
                value =
                        latched_ultrasonic[ultrasonic_i2c_context
                                                   .register_address];
            }
            i2c_write_byte_raw(i2c, value);
            ultrasonic_i2c_context.register_address++;
            break;
        }

        case I2C_SLAVE_FINISH:
            ultrasonic_i2c_context.register_written = false;
            break;

        default:
            break;
    }
}

// Entire ultrasonic bridge, start to finish, on core1. i2c_slave_init() must be
// reached from this core: irq_set_enabled() inside it only sets the enable bit
// in the executing core's NVIC, and that is what keeps the I2C1 interrupt off
// core0's UART parser. Nothing here may call printf; the stdio mutexes are
// shared with core0's status output and would couple the two cores again.
static void core1_entry(void) {
    build_startup_ultrasonic();

    gpio_init(ULTRASONIC_TRIGGER_PIN);
    gpio_set_dir(ULTRASONIC_TRIGGER_PIN, GPIO_OUT);
    gpio_put(ULTRASONIC_TRIGGER_PIN, 0);

    gpio_init(ULTRASONIC_ECHO_PIN);
    gpio_set_dir(ULTRASONIC_ECHO_PIN, GPIO_IN);

    // Bring the bus up and register the handler without interruption. If i2c1
    // starts acknowledging its address before its handler is enabled, the RX
    // FIFO fills, clock stretching engages, and the REV port can wedge.
    gpio_init(ULTRASONIC_I2C_SDA_PIN);
    gpio_set_function(ULTRASONIC_I2C_SDA_PIN, GPIO_FUNC_I2C);
    gpio_pull_up(ULTRASONIC_I2C_SDA_PIN);

    gpio_init(ULTRASONIC_I2C_SCL_PIN);
    gpio_set_function(ULTRASONIC_I2C_SCL_PIN, GPIO_FUNC_I2C);
    gpio_pull_up(ULTRASONIC_I2C_SCL_PIN);

    i2c_init(ULTRASONIC_I2C_PORT, ULTRASONIC_I2C_BAUDRATE);
    i2c_slave_init(
            ULTRASONIC_I2C_PORT,
            ULTRASONIC_I2C_ADDRESS,
            ultrasonic_i2c_handler);

    uint16_t sample_seq = 0;
    while (true) {
        absolute_time_t cycle_start = get_absolute_time();

        uint16_t distance_mm = read_ultrasonic_distance_mm();
        sample_seq++;
        publish_ultrasonic(distance_mm, sample_seq);

        sleep_until(delayed_by_ms(cycle_start, ULTRASONIC_CYCLE_MS));
    }
}

static void setup_uart(void) {
    uart_init(UART_PORT, UART_BAUDRATE);
    gpio_set_function(UART_TX_PIN, GPIO_FUNC_UART);
    gpio_set_function(UART_RX_PIN, GPIO_FUNC_UART);
    uart_set_format(UART_PORT, 8, 1, UART_PARITY_NONE);
    uart_set_fifo_enabled(UART_PORT, true);
}

static void setup_i2c(void) {
    gpio_init(I2C_SDA_PIN);
    gpio_set_function(I2C_SDA_PIN, GPIO_FUNC_I2C);
    gpio_pull_up(I2C_SDA_PIN);

    gpio_init(I2C_SCL_PIN);
    gpio_set_function(I2C_SCL_PIN, GPIO_FUNC_I2C);
    gpio_pull_up(I2C_SCL_PIN);

    i2c_init(I2C_PORT, I2C_BAUDRATE);
    i2c_slave_init(I2C_PORT, I2C_ADDRESS, i2c_slave_handler);
}

int main(void) {
    stdio_init_all();
    build_startup_snapshot();
    setup_uart();
    setup_i2c();

    sleep_ms(1500);
    printf("Fable Pico bridge ready: UART0 GP1(RX)/GP0(TX), I2C0 GP4/GP5 @ 0x42\n");

    multicore_launch_core1(core1_entry);

    absolute_time_t next_status = make_timeout_time_ms(1000);
    while (true) {
        while (uart_is_readable(UART_PORT)) {
            consume_uart_byte(uart_getc(UART_PORT));
        }

        if (time_reached(next_status)) {
            printf("uart_ok=%lu uart_bad=%lu i2c_transactions=%lu\n",
                   (unsigned long)accepted_frames,
                   (unsigned long)rejected_frames,
                   (unsigned long)i2c_transactions);
            next_status = make_timeout_time_ms(1000);
        }
        tight_loop_contents();
    }
}
