package com.stegohx.backend.core;

/**
 * Domain error for stego operations (unsupported media, payload too large,
 * missing container, wrong key...). Mapped to HTTP 422 by the global
 * exception handler.
 */
public class StegoException extends RuntimeException {

    public StegoException(String message) {
        super(message);
    }

    public StegoException(String message, Throwable cause) {
        super(message, cause);
    }
}
