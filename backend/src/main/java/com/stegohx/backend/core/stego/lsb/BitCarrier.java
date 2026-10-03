package com.stegohx.backend.core.stego.lsb;

/**
 * An addressable sequence of carriers. getBits/setBits move the carrier's
 * full least-significant byte (pixel channel byte / audio low byte);
 * bit-plane masking for the active bit depth is handled by
 * {@link LsbEmbedder} via read-modify-write, so carriers stay depth-agnostic
 * (extraction probes multiple depths against the same carrier).
 */
public interface BitCarrier {

    int carrierCount();

    /** Read the low byte of carrier {@code index}. */
    int getBits(int index);

    /** Write the low byte of carrier {@code index}. */
    void setBits(int index, int value);
}
