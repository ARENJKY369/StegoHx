package com.stegohx.backend.core.stego.lsb;

/**
 * Keyed bijection over carrier positions for seeded (scattered) embedding.
 *
 * A 4-round Feistel network over the smallest even bit-width that covers the
 * carrier domain, with cycle-walking to stay inside the domain. This gives a
 * reproducible permutation with O(1) memory and O(1) lookup - no shuffled
 * index arrays (which would cost hundreds of MB for large covers).
 *
 * Not a cryptographic primitive: it exists so that payload placement is
 * pseudorandom and key-derived. Payload confidentiality is provided by the
 * {@link com.stegohx.backend.core.codec.PayloadCodec} keystream.
 */
public final class PositionMapping {

    private PositionMapping() {
    }

    /**
     * Map stream index {@code i} (0-based, over a domain of size {@code m})
     * to a carrier offset. Identity when {@code seeded} is false.
     */
    public static long map(long i, long m, long seed, boolean seeded) {
        if (!seeded || m <= 1) {
            return i;
        }
        int k = 64 - Long.numberOfLeadingZeros(m - 1);
        if ((k & 1) == 1) {
            k++;
        }
        long limit = 1L << k;
        long x = i;
        do {
            x = feistel(x, k, seed);
        } while (x >= m);
        return x;
    }

    private static long feistel(long x, int k, long seed) {
        int half = k / 2;
        long mask = (1L << half) - 1;
        long left = (x >>> half) & mask;
        long right = x & mask;
        for (int round = 0; round < 4; round++) {
            long f = mix(seed ^ Long.rotateLeft(0x9E3779B97F4A7C15L, round * 13) ^ right) & mask;
            long newRight = left ^ f;
            left = right;
            right = newRight;
        }
        return (left << half) | right;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
