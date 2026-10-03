package com.stegohx.backend.core.stego;

/**
 * A steganographic carrier module. Implementations are stateless; all state
 * lives in the parameters and the byte arrays.
 */
public interface StegoModule {

    String id();

    String displayName();

    String description();

    MediaKind mediaKind();

    /** True when this module can handle the given bytes/filename. */
    boolean supports(byte[] data, String filename);

    /** Usable payload bytes for the given cover (header overhead excluded). */
    long capacityBytes(byte[] data, EmbedOptions options);

    StegoEmbedResult embed(byte[] cover, byte[] payload, EmbedOptions options);

    /**
     * Recover a payload. {@code seed} must match the one used at embed time
     * when an explicit (non key-derived) seed was chosen; container-level
     * modules ignore it.
     */
    StegoExtractResult extract(byte[] data, String key, long seed);

    CleanResult clean(byte[] data);

    /** Modules that can operate without a cover file (text, network codecs). */
    default boolean requiresCover() {
        return true;
    }

    /** Result of an embed operation. */
    record StegoEmbedResult(
            byte[] output,
            String outputMime,
            String outputName,
            long payloadBytes,
            double capacityUsedRatio) {
    }

    record StegoExtractResult(byte[] payload, boolean containerVerified) {
    }

    record CleanResult(byte[] output, String outputMime, String note) {
    }
}
