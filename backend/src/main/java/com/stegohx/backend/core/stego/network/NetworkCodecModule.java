package com.stegohx.backend.core.stego.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.codec.PayloadCodec;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.MediaKind;
import com.stegohx.backend.core.stego.StegoModule;

/**
 * Network transport simulation codec.
 *
 * Encodes a keyed payload into the shape of carrier traffic artifacts -
 * either a stream of DNS query labels or a set of HTTP header fragments -
 * and decodes them back. This is a pure offline codec for defensive training
 * (building detection corpora, practicing payload reconstruction) and for
 * demonstrating how payloads chunk across covert channels. No sockets are
 * opened and no traffic is generated.
 */
public class NetworkCodecModule implements StegoModule {

    private static final String HEADER = "# StegoHX simulated transport script (offline codec - no traffic generated)";
    private static final int CHUNK = 56; // Base32 chars per fragment

    private static final Pattern DNS_LINE = Pattern.compile("q(\\d+)\\.([A-Z2-7]+)\\.stgx");
    private static final Pattern HTTP_LINE = Pattern.compile("X-Session-Fragment-(\\d+):\\s*([A-Z2-7]+)");

    @Override
    public String id() {
        return "network_transport";
    }

    @Override
    public String displayName() {
        return "Network Transport Codec (DNS / HTTP shaping)";
    }

    @Override
    public String description() {
        return "Chunks the keyed payload into Base32 fragments shaped as DNS query labels or "
                + "HTTP header lines - an offline simulation of network covert-channel traffic "
                + "for detection training. No traffic is generated.";
    }

    @Override
    public MediaKind mediaKind() {
        return MediaKind.NETWORK;
    }

    @Override
    public boolean requiresCover() {
        return false;
    }

    @Override
    public boolean supports(byte[] data, String filename) {
        if (filename != null && (filename.toLowerCase(Locale.ROOT).endsWith(".stgxnet")
                || filename.toLowerCase(Locale.ROOT).endsWith(".dns")
                || filename.toLowerCase(Locale.ROOT).endsWith(".http"))) {
            return true;
        }
        if (data == null || data.length == 0) {
            return false;
        }
        String head = new String(data, 0, Math.min(data.length, 256), StandardCharsets.UTF_8);
        return head.startsWith(HEADER);
    }

    @Override
    public long capacityBytes(byte[] data, EmbedOptions options) {
        return 64L * 1024 * 1024;
    }

    @Override
    public StegoEmbedResult embed(byte[] cover, byte[] payload, EmbedOptions options) {
        byte[] container = PayloadCodec.encode(payload, options.key());
        String b32 = base32Encode(container);
        boolean http = "http".equalsIgnoreCase(options.variant());
        StringBuilder out = new StringBuilder(HEADER).append('\n')
                .append("# format: ").append(http ? "http-header-fragments" : "dns-query-stream").append('\n');
        List<String> chunks = chunk(b32, CHUNK);
        for (int i = 0; i < chunks.size(); i++) {
            if (http) {
                out.append("X-Session-Fragment-").append(i + 1).append(": ").append(chunks.get(i)).append('\n');
            } else {
                out.append("q").append(i + 1).append(".").append(chunks.get(i)).append(".stgx.invalid\n");
            }
        }
        return new StegoEmbedResult(out.toString().getBytes(StandardCharsets.UTF_8), "text/plain",
                http ? "stegohx_transport.http" : "stegohx_transport.dns", payload.length, 0.0);
    }

    @Override
    public StegoExtractResult extract(byte[] data, String key, long seed) {
        if (data == null || data.length == 0) {
            throw new StegoException("empty input");
        }
        String text = new String(data, StandardCharsets.UTF_8);
        List<String> fragments = new ArrayList<>();
        Matcher dns = DNS_LINE.matcher(text);
        while (dns.find()) {
            fragments.add(dns.group(2));
        }
        if (fragments.isEmpty()) {
            Matcher http = HTTP_LINE.matcher(text);
            while (http.find()) {
                fragments.add(http.group(2));
            }
        }
        if (fragments.isEmpty()) {
            throw new StegoException("no StegoHX transport fragments found (expected q*.*.stgx or X-Session-Fragment lines)");
        }
        String b32 = String.join("", fragments);
        byte[] container;
        try {
            container = base32Decode(b32);
        } catch (IllegalArgumentException e) {
            throw new StegoException("fragments do not form valid Base32", e);
        }
        return new StegoExtractResult(PayloadCodec.decode(container, key), true);
    }

    @Override
    public CleanResult clean(byte[] data) {
        return new CleanResult(data, "text/plain",
                "transport scripts are generated artifacts; nothing embedded to remove");
    }

    // --- Base32 (RFC 4648) ---------------------------------------------------

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
        ByteArrayOutputStreamLike out = new ByteArrayOutputStreamLike(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("bad Base32 character: " + c);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static List<String> chunk(String s, int size) {
        List<String> chunks = new ArrayList<>((s.length() + size - 1) / size);
        for (int i = 0; i < s.length(); i += size) {
            chunks.add(s.substring(i, Math.min(s.length(), i + size)));
        }
        return chunks;
    }

    /** Minimal growable byte sink (avoids java.io.ByteArrayOutputStream's
     *  checked IOException noise for this pure in-memory case). */
    private static final class ByteArrayOutputStreamLike {
        private byte[] buf = new byte[64];
        private int len;

        void write(int b) {
            if (len == buf.length) {
                byte[] next = new byte[buf.length * 2];
                System.arraycopy(buf, 0, next, 0, len);
                buf = next;
            }
            buf[len++] = (byte) b;
        }

        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
