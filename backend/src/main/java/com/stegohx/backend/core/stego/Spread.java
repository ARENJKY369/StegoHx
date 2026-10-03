package com.stegohx.backend.core.stego;

/** Payload placement strategy. */
public enum Spread {
    /** Payload bits follow the header sequentially. */
    SEQUENTIAL,
    /** Payload bits are placed at pseudo-random carrier positions derived
     *  from the key seed (keyed bijection, O(1) memory). */
    SEEDED
}
