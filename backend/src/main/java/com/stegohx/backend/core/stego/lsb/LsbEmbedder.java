package com.stegohx.backend.core.stego.lsb;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.codec.PayloadCodec;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.Spread;

/**
 * Bit-plane embedding over a {@link BitCarrier}.
 *
 * Layout (in carrier order):
 *
 * <pre>
 * [ 96-bit stream header, always sequential ]
 *     - "STGX" magic (32 bits)
 *     - container length in bytes (32 bits)
 *     - flags (16 bits): bits 0-1 = bitDepth-1, bit 2 = seeded spread
 *     - reserved (16 bits, zero)
 * [ container bits: PayloadCodec output, bitDepth bits per carrier ]
 *     - sequential, or scattered via the keyed position permutation
 * </pre>
 *
 * The header is always written sequentially and self-describes the payload
 * placement, so extraction only needs the key (and the original explicit
 * seed, if one was used instead of the key-derived default).
 */
public final class LsbEmbedder {

    private static final int HEADER_BITS = 96;
    private static final int MAGIC_BITS = 32;
    private static final int LENGTH_BITS = 32;
    private static final int FLAGS_BITS = 16;

    private LsbEmbedder() {
    }

    /** Extraction parameters: the key, plus the explicit seed if one was used. */
    public record ExtractOptions(String key, long seed) {
        public ExtractOptions {
            if (key == null) {
                key = "";
            }
        }
    }

    public static long capacityBytes(BitCarrier carrier, int bitDepth) {
        long payloadCarriers = carrier.carrierCount() - headerCarriers(bitDepth);
        if (payloadCarriers <= 0) {
            return 0;
        }
        return payloadCarriers * (long) bitDepth / 8;
    }

    public static void embed(BitCarrier carrier, byte[] payload, EmbedOptions options) {
        int d = options.bitDepth();
        int n = carrier.carrierCount();
        int headerCarriers = headerCarriers(d);
        byte[] container = PayloadCodec.encode(payload, options.key());
        long payloadCarriers = ((long) container.length * 8 + d - 1) / d;
        if (headerCarriers + payloadCarriers > n) {
            throw new StegoException(String.format(
                    "payload too large for cover: need %d carriers, have %d (capacity %d bytes)",
                    headerCarriers + payloadCarriers, n, capacityBytes(carrier, d)));
        }
        long domain = n - headerCarriers;
        long seed = options.effectiveSeed();
        boolean seeded = options.spread() == Spread.SEEDED;
        int flags = ((d - 1) & 0b11) | ((seeded ? 1 : 0) << 2);

        writeWord(carrier, 0, d, MAGIC_BITS, magicValue());
        writeWord(carrier, wordStart(d, 0), d, LENGTH_BITS, container.length);
        writeWord(carrier, wordStart(d, 1), d, FLAGS_BITS, flags);

        int mask = (1 << d) - 1;
        for (long i = 0; i < payloadCarriers; i++) {
            long carrierIdx = headerCarriers + PositionMapping.map(i, domain, seed, seeded);
            int value = readStreamBits(container, i * d, d);
            int old = carrier.getBits((int) carrierIdx);
            carrier.setBits((int) carrierIdx, (old & ~mask) | (value & mask));
        }
    }

    /**
     * Locate and decode a payload. Probes bit depths 1 and 2 (the header
     * encoding depends on the depth, so a wrong probe yields a bad magic).
     */
    public static byte[] extract(BitCarrier carrier, ExtractOptions options) {
        for (int d = 1; d <= 2; d++) {
            int headerCarriers = headerCarriers(d);
            if (carrier.carrierCount() <= headerCarriers) {
                continue;
            }
            if (readWord(carrier, 0, d, MAGIC_BITS) != magicValue()) {
                continue;
            }
            long containerLen = readWord(carrier, wordStart(d, 0), d, LENGTH_BITS);
            int flags = (int) readWord(carrier, wordStart(d, 1), d, FLAGS_BITS);
            int embedDepth = (flags & 0b11) + 1;
            boolean seeded = ((flags >> 2) & 1) == 1;
            if (embedDepth != d) {
                throw new StegoException("corrupt payload header (depth mismatch)");
            }
            if (containerLen <= 0 || containerLen > 64L * 1024 * 1024) {
                throw new StegoException("corrupt payload header (implausible length)");
            }
            long payloadCarriers = (containerLen * 8 + d - 1) / d;
            if (headerCarriers + payloadCarriers > carrier.carrierCount()) {
                throw new StegoException("corrupt payload header (payload exceeds carrier size)");
            }
            long domain = carrier.carrierCount() - headerCarriers;
            long seed = options.seed() != 0L ? options.seed() : EmbedOptions.defaults().withKey(options.key()).effectiveSeed();
            byte[] container = new byte[(int) containerLen];
            int mask = (1 << d) - 1;
            for (long i = 0; i < payloadCarriers; i++) {
                long carrierIdx = headerCarriers + PositionMapping.map(i, domain, seed, seeded);
                int value = carrier.getBits((int) carrierIdx) & mask;
                writeStreamBits(container, i * d, d, value);
            }
            return PayloadCodec.decode(container, options.key());
        }
        throw new StegoException("no StegoHX payload found in this file");
    }

    /** Zero the low {@code bitDepth} bits of every carrier (sanitize). */
    public static void scrub(BitCarrier carrier, int bitDepth) {
        int mask = (1 << bitDepth) - 1;
        for (int i = 0; i < carrier.carrierCount(); i++) {
            int old = carrier.getBits(i);
            carrier.setBits(i, old & ~mask);
        }
    }

    // --- helpers -------------------------------------------------------------

    private static int headerCarriers(int d) {
        return HEADER_BITS / d;
    }

    /** Carrier index where word {@code w} (0=length, 1=flags) starts. */
    private static int wordStart(int d, int w) {
        return (MAGIC_BITS + w * LENGTH_BITS) / d;
    }

    private static long magicValue() {
        return ((long) (PayloadCodec.MAGIC[0] & 0xFF) << 24)
                | ((long) (PayloadCodec.MAGIC[1] & 0xFF) << 16)
                | ((long) (PayloadCodec.MAGIC[2] & 0xFF) << 8)
                | (PayloadCodec.MAGIC[3] & 0xFF);
    }

    /** Write the low {@code bits} bits of {@code value}, LSB-first. */
    private static void writeWord(BitCarrier carrier, int startCarrier, int d, int bits, long value) {
        int carriers = (bits + d - 1) / d;
        for (int i = 0; i < carriers; i++) {
            int shift = i * d;
            int width = Math.min(d, bits - shift);
            int v = (int) ((value >>> shift) & ((1 << width) - 1));
            carrier.setBits(startCarrier + i, v);
        }
    }

    private static long readWord(BitCarrier carrier, int startCarrier, int d, int bits) {
        int carriers = (bits + d - 1) / d;
        long value = 0;
        for (int i = 0; i < carriers; i++) {
            int shift = i * d;
            int width = Math.min(d, bits - shift);
            long v = carrier.getBits(startCarrier + i) & ((1 << width) - 1);
            value |= v << shift;
        }
        return value;
    }

    /** Read {@code count} bits from a byte array bit stream, LSB-first. */
    private static int readStreamBits(byte[] data, long bitOffset, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            long bitIdx = bitOffset + i;
            int bit = (data[(int) (bitIdx / 8)] >> (int) (bitIdx % 8)) & 1;
            value |= bit << i;
        }
        return value;
    }

    /** Write {@code count} bits into a byte array bit stream, LSB-first. */
    private static void writeStreamBits(byte[] data, long bitOffset, int count, int value) {
        for (int i = 0; i < count; i++) {
            long bitIdx = bitOffset + i;
            int byteIdx = (int) (bitIdx / 8);
            int bitInByte = (int) (bitIdx % 8);
            if (((value >> i) & 1) == 1) {
                data[byteIdx] |= (byte) (1 << bitInByte);
            } else {
                data[byteIdx] &= (byte) ~(1 << bitInByte);
            }
        }
    }
}
