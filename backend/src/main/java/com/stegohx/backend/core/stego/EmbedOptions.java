package com.stegohx.backend.core.stego;

/**
 * Embedding parameters.
 *
 * @param key      passphrase used for the payload keystream (and seed)
 * @param bitDepth bits replaced per carrier (1-2; image/audio modules only)
 * @param spread   sequential or seeded placement
 * @param seed     explicit seed (0 = derive from key hash)
 * @param variant  module-specific hint (e.g. network module: "dns" | "http")
 */
public record EmbedOptions(String key, int bitDepth, Spread spread, long seed, String variant) {

    public static EmbedOptions defaults() {
        return new EmbedOptions("", 1, Spread.SEQUENTIAL, 0L, "dns");
    }

    /** Copy of these options with a different key. */
    public EmbedOptions withKey(String newKey) {
        return new EmbedOptions(newKey, bitDepth, spread, seed, variant);
    }

    public EmbedOptions {
        if (bitDepth < 1 || bitDepth > 2) {
            throw new IllegalArgumentException("bitDepth must be 1 or 2");
        }
        if (spread == null) {
            spread = Spread.SEQUENTIAL;
        }
        if (key == null) {
            key = "";
        }
        if (variant == null) {
            variant = "dns";
        }
    }

    /** Effective seed: explicit seed, or one derived from the key. */
    public long effectiveSeed() {
        if (seed != 0L) {
            return seed;
        }
        long h = 1125899906842597L; // FNV-ish fold of the key
        for (byte b : key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h = 31 * h + b;
        }
        return h;
    }
}
