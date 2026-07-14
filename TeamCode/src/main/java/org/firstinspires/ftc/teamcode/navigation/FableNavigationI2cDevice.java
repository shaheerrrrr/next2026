package org.firstinspires.ftc.teamcode.navigation;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties;
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** FTC hardware driver for Fable's robot-side navigation ESP32-C3. */
@I2cDeviceType
@DeviceProperties(
        name = "Fable ESP Navigation",
        description = "GPS and target data from Fable's robot-side ESP32-C3",
        xmlTag = "FableEspNavigation")
public class FableNavigationI2cDevice
        extends I2cDeviceSynchDevice<I2cDeviceSynchSimple> {
    public static final int I2C_ADDRESS_7_BIT = 0x42;
    public static final int PACKET_REGISTER = 0x00;
    public static final int PACKET_LENGTH = 56;
    public static final int PROTOCOL_VERSION = 1;

    public static final int FLAG_LOCATION_VALID = 1 << 0;
    public static final int FLAG_TARGET_VALID = 1 << 1;
    public static final int FLAG_SATELLITES_VALID = 1 << 2;
    public static final int FLAG_HDOP_VALID = 1 << 3;
    public static final int FLAG_DRIVER_LINK_ALIVE = 1 << 4;

    private static final byte MAGIC_0 = 'F';
    private static final byte MAGIC_1 = 'N';
    private static final byte MAGIC_2 = 'A';
    private static final byte MAGIC_3 = 'V';

    public FableNavigationI2cDevice(
            I2cDeviceSynchSimple deviceClient,
            boolean deviceClientIsOwned) {
        super(deviceClient, deviceClientIsOwned);
        this.deviceClient.setI2cAddress(I2cAddr.create7bit(I2C_ADDRESS_7_BIT));
        registerArmingStateCallback(false);
        engage();
    }

    @Override
    protected boolean doInitialize() {
        // Packet validity is checked on every read. Initialization must not fail merely because
        // the ESP is still booting or does not yet have a GPS fix.
        deviceClient.setI2cAddress(I2cAddr.create7bit(I2C_ADDRESS_7_BIT));
        return true;
    }

    /**
     * Performs one explicit I2C transaction. No background read window is configured, so the
     * Control Hub only polls the ESP when this method is called.
     */
    public EspNavigationData readNavigationData() {
        long readTimeNanos = System.nanoTime();
        final byte[] bytes;

        try {
            bytes = deviceClient.read(PACKET_REGISTER, PACKET_LENGTH);
        } catch (RuntimeException e) {
            return EspNavigationData.invalid(
                    EspNavigationData.Status.I2C_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    readTimeNanos);
        }

        if (bytes == null || bytes.length != PACKET_LENGTH) {
            int actualLength = bytes == null ? -1 : bytes.length;
            return EspNavigationData.invalid(
                    EspNavigationData.Status.WRONG_LENGTH,
                    "Expected " + PACKET_LENGTH + " bytes, received " + actualLength,
                    readTimeNanos);
        }

        if (bytes[0] != MAGIC_0
                || bytes[1] != MAGIC_1
                || bytes[2] != MAGIC_2
                || bytes[3] != MAGIC_3) {
            return EspNavigationData.invalid(
                    EspNavigationData.Status.BAD_MAGIC,
                    "Packet magic was not FNAV",
                    readTimeNanos);
        }

        int version = unsignedByte(bytes[4]);
        if (version != PROTOCOL_VERSION) {
            return EspNavigationData.invalid(
                    EspNavigationData.Status.BAD_VERSION,
                    "Expected protocol " + PROTOCOL_VERSION + ", received " + version,
                    readTimeNanos);
        }

        int encodedLength = unsignedByte(bytes[5]);
        if (encodedLength != PACKET_LENGTH) {
            return EspNavigationData.invalid(
                    EspNavigationData.Status.BAD_PACKET_LENGTH,
                    "Packet declares " + encodedLength + " bytes",
                    readTimeNanos);
        }

        int expectedCrc = unsignedShortLittleEndian(bytes, PACKET_LENGTH - 2);
        int actualCrc = crc16Ccitt(bytes, 0, PACKET_LENGTH - 2);
        if (expectedCrc != actualCrc) {
            return EspNavigationData.invalid(
                    EspNavigationData.Status.BAD_CHECKSUM,
                    String.format("CRC expected 0x%04X, calculated 0x%04X", expectedCrc, actualCrc),
                    readTimeNanos);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int flags = unsignedByte(bytes[6]);
        EspNavigationData.FixQuality fixQuality =
                EspNavigationData.FixQuality.fromWireValue(unsignedByte(bytes[7]));

        return new EspNavigationData(
                EspNavigationData.Status.OK,
                "",
                readTimeNanos,
                flags,
                fixQuality,
                unsignedInt(buffer.getInt(8)),
                unsignedInt(buffer.getInt(12)),
                buffer.getInt(16) / 10_000_000.0,
                buffer.getInt(20) / 10_000_000.0,
                buffer.getInt(24) / 10_000_000.0,
                buffer.getInt(28) / 10_000_000.0,
                unsignedInt(buffer.getInt(32)),
                unsignedInt(buffer.getInt(36)),
                unsignedShortLittleEndian(bytes, 40) / 100.0,
                unsignedByte(bytes[42]),
                unsignedInt(buffer.getInt(44)),
                unsignedInt(buffer.getInt(48)));
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF, no reflection. */
    public static int crc16Ccitt(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= unsignedByte(data[i]) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                        ? ((crc << 1) ^ 0x1021) & 0xFFFF
                        : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    @Override
    public Manufacturer getManufacturer() {
        return HardwareDevice.Manufacturer.Other;
    }

    @Override
    public String getDeviceName() {
        return "Fable ESP Navigation";
    }

    private static int unsignedByte(byte value) {
        return value & 0xFF;
    }

    private static int unsignedShortLittleEndian(byte[] bytes, int offset) {
        return unsignedByte(bytes[offset]) | (unsignedByte(bytes[offset + 1]) << 8);
    }

    private static long unsignedInt(int value) {
        return value & 0xFFFF_FFFFL;
    }
}
