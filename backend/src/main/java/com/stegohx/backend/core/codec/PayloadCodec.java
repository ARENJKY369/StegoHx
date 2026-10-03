package com.stegohx.backend.core.codec;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.stegohx.backend.core.StegoException;

/**
 * Payload container format + key stream.
 *
 * Container layout:
 *
 * <pre>
 * [ "STGX" 4B ][ version=1, 1B ][ flags=0, 1B ][ payload length, 4B BE ][ payload', N bytes ]
 * </pre>
 *
 * The payload is XOR-ed with a keystream derived from HMAC-SHA256(key,
 * counter). This is an obfuscation layer keyed by the user passphrase - it
 * keeps casual inspection from revealing embedded content and makes
 * extraction require the key, but it is not a substitute for authenticated
 * encryption if confidentiality matters.
 */
public final class PayloadCodec {

    public static final byte[] MAGIC = {'S', 'T', 'G', 'X'};
    public static final int HEADER_BYTES = 10;
    private static final byte VERSION = 1;

    private PayloadCodec() {
    }

    public static byte[] encode(byte[] payload, String key) {
        byte[] keyBytes = keyBytes(key);
        byte[] cipher = xorKeystream(payload, keyBytes);
        byte[] out = new byte[HEADER_BYTES + cipher.length];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = 0; // flags reserved
        int len = payload.length;
        out[6] = (byte) (len >>> 24);
        out[7] = (byte) (len >>> 16);
        out[8] = (byte) (len >>> 8);
        out[9] = (byte) len;
        System.arraycopy(cipher, 0, out, HEADER_BYTES, cipher.length);
        return out;
    }

    public static byte[] decode(byte[] container, String key) {
        if (container == null || container.length < HEADER_BYTES) {
            throw new StegoException("no StegoHX payload container present (too short)");
        }
        for (int i = 0; i < 4; i++) {
            if (container[i] != MAGIC[i]) {
                throw new StegoException("no StegoHX payload container present (bad magic)");
            }
        }
        if (container[4] != VERSION) {
            throw new StegoException("unsupported container version: " + container[4]);
        }
        int len = ((container[6] & 0xFF) << 24) | ((container[7] & 0xFF) << 16)
                | ((container[8] & 0xFF) << 8) | (container[9] & 0xFF);
        if (len < 0 || HEADER_BYTES + len > container.length) {
            throw new StegoException("corrupt payload container (length out of bounds)");
        }
        byte[] cipher = new byte[len];
        System.arraycopy(container, HEADER_BYTES, cipher, 0, len);
        byte[] plain = xorKeystream(cipher, keyBytes(key));
        // sanity: the key must produce a non-degenerate first byte only if
        // payload was text - no strong check possible without a MAC, but
        // length integrity was already verified by the container.
        return plain;
    }

    private static byte[] keyBytes(String key) {
        return (key == null || key.isBlank() ? "stegohx-default" : key).getBytes(StandardCharsets.UTF_8);
    }

    /** XOR payload with HMAC-SHA256(key, counter) blocks. */
    private static byte[] xorKeystream(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = new byte[data.length];
            long counter = 0;
            int offset = 0;
            byte[] block = null;
            while (offset < data.length) {
                byte[] ctr = new byte[8];
                for (int i = 0; i < 8; i++) {
                    ctr[7 - i] = (byte) (counter >>> (8 * i));
                }
                block = mac.doFinal(ctr);
                for (int i = 0; i < 32 && offset < data.length; i++, offset++) {
                    out[offset] = (byte) (data[offset] ^ block[i]);
                }
                counter++;
            }
            return out;
        } catch (Exception e) {
            throw new StegoException("keystream generation failed", e);
        }
    }
}
