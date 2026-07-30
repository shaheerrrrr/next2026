package org.firstinspires.ftc.teamcode.navigation;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.I2cWaitControl;
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties;
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType;

/** FTC hardware driver for Fable's HC-SR04 rangefinder on the RP2040 bridge's second I2C bus. */
@I2cDeviceType
@DeviceProperties(
        name = "Fable Ultrasonic",
        description = "Forward proximity distance from Fable's RP2040 ultrasonic bridge",
        xmlTag = "FableUltrasonic")
public class UltrasonicI2cDevice
        extends I2cDeviceSynchDevice<I2cDeviceSynchSimple> {
    public static final int I2C_ADDRESS_7_BIT = 0x43;
    public static final int PACKET_REGISTER = 0x00;
    public static final int PACKET_LENGTH = 12;
    public static final int PROTOCOL_VERSION = 1;

    private static final byte MAGIC_0 = 'F';
    private static final byte MAGIC_1 = 'S';
    private static final byte MAGIC_2 = 'O';
    private static final byte MAGIC_3 = 'N';

    public UltrasonicI2cDevice(
            I2cDeviceSynchSimple deviceClient,
            boolean deviceClientIsOwned) {
        super(deviceClient, deviceClientIsOwned);
        this.deviceClient.setI2cAddress(I2cAddr.create7bit(I2C_ADDRESS_7_BIT));
        registerArmingStateCallback(false);
        engage();
    }

    @Override
    protected boolean doInitialize() {
        // Frame validity is checked on every read. Initialization must not fail merely because
        // the Pico is still booting or has not yet completed a ping cycle.
        deviceClient.setI2cAddress(I2cAddr.create7bit(I2C_ADDRESS_7_BIT));
        return true;
    }

    /** Performs one short I2C read. No background read window is configured. */
    public UltrasonicReading readDistance() {
        long readTimeNanos = System.nanoTime();
        final byte[] bytes;

        try {
            // Keep register selection and reading as separate bus transactions. The Pico
            // handles the register write synchronously and needs no artificial delay. The
            // whole 12-byte frame fits in one read, so no chunking is required.
            deviceClient.write8(PACKET_REGISTER, I2cWaitControl.WRITTEN);
            bytes = deviceClient.read(PACKET_LENGTH);
        } catch (RuntimeException e) {
            return UltrasonicReading.invalid(
                    UltrasonicReading.Status.I2C_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    readTimeNanos);
        }

        if (bytes == null || bytes.length != PACKET_LENGTH) {
            int actualLength = bytes == null ? -1 : bytes.length;
            return UltrasonicReading.invalid(
                    UltrasonicReading.Status.WRONG_LENGTH,
                    String.format(
                            "Register 0x%02X expected %d bytes, received %d",
                            PACKET_REGISTER,
                            PACKET_LENGTH,
                            actualLength),
                    readTimeNanos);
        }

        if (bytes[0] != MAGIC_0
                || bytes[1] != MAGIC_1
                || bytes[2] != MAGIC_2
                || bytes[3] != MAGIC_3) {
            return UltrasonicReading.invalid(
                    UltrasonicReading.Status.BAD_MAGIC,
                    "Frame magic was not FSON; " + diagnosticHex(bytes),
                    readTimeNanos);
        }

        int version = unsignedByte(bytes[4]);
        if (version != PROTOCOL_VERSION) {
            return UltrasonicReading.invalid(
                    UltrasonicReading.Status.BAD_VERSION,
                    "Expected protocol " + PROTOCOL_VERSION + ", received " + version,
                    readTimeNanos);
        }

        int encodedLength = unsignedByte(bytes[5]);
        if (encodedLength != PACKET_LENGTH) {
            return UltrasonicReading.invalid(
                    UltrasonicReading.Status.BAD_PACKET_LENGTH,
                    "Frame declares " + encodedLength + " bytes",
                    readTimeNanos);
        }

        int expectedCrc = unsignedShortLittleEndian(bytes, PACKET_LENGTH - 2);
        // Reuses the GPS driver's CRC-16/CCITT-FALSE implementation; both frames use the
        // identical parameters, so there is deliberately only one copy of the algorithm.
        int actualCrc = FableNavigationI2cDevice.crc16Ccitt(bytes, 0, PACKET_LENGTH - 2);
        if (expectedCrc != actualCrc) {
            return UltrasonicReading.invalid(
                    UltrasonicReading.Status.BAD_CHECKSUM,
                    String.format(
                            "CRC expected 0x%04X, calculated 0x%04X; %s",
                            expectedCrc,
                            actualCrc,
                            diagnosticHex(bytes)),
                    readTimeNanos);
        }

        return new UltrasonicReading(
                UltrasonicReading.Status.OK,
                "",
                readTimeNanos,
                unsignedShortLittleEndian(bytes, 8),
                unsignedShortLittleEndian(bytes, 6));
    }

    @Override
    public Manufacturer getManufacturer() {
        return HardwareDevice.Manufacturer.Other;
    }

    @Override
    public String getDeviceName() {
        return "Fable Ultrasonic";
    }

    private static int unsignedByte(byte value) {
        return value & 0xFF;
    }

    private static int unsignedShortLittleEndian(byte[] bytes, int offset) {
        return unsignedByte(bytes[offset]) | (unsignedByte(bytes[offset + 1]) << 8);
    }

    private static String diagnosticHex(byte[] bytes) {
        StringBuilder result = new StringBuilder("bytes=");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format("%02X", unsignedByte(bytes[i])));
        }
        return result.toString();
    }
}
