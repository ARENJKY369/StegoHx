package com.stegohx.backend.core.stego.text;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.codec.PayloadCodec;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.MediaKind;
import com.stegohx.backend.core.stego.StegoModule;

/**
 * Text module: armored payload blocks. The keyed payload container is
 * Base64-encoded and wrapped in PEM-style markers, optionally appended to an
 * existing cover text. This is transport-level text steganography (the
 * payload is visible as an opaque block); it exists for completeness of the
 * module matrix and for round-trip testing of the codec.
 */
public class TextStegoModule implements StegoModule {

    static final String BEGIN = "-----BEGIN STEGOHX DATA-----";
    static final String END = "-----END STEGOHX DATA-----";
    private static final Pattern BLOCK = Pattern.compile(
            Pattern.quote(BEGIN) + "\\s*([A-Za-z0-9+/=\\s]+?)\\s*" + Pattern.quote(END));

    @Override
    public String id() {
        return "text_armored";
    }

    @Override
    public String displayName() {
        return "Text Armored Block (XOR + Base64)";
    }

    @Override
    public String description() {
        return "Encodes the payload with the keyed XOR container codec and wraps it in "
                + "PEM-style Base64 armor, optionally appended to cover text.";
    }

    @Override
    public MediaKind mediaKind() {
        return MediaKind.TEXT;
    }

    @Override
    public boolean requiresCover() {
        return false;
    }

    @Override
    public boolean supports(byte[] data, String filename) {
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            return true;
        }
        if (data == null || data.length == 0) {
            return true; // no-cover encode
        }
        String head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.UTF_8);
        return head.startsWith(BEGIN) || head.contains(BEGIN);
    }

    @Override
    public long capacityBytes(byte[] data, EmbedOptions options) {
        return 64L * 1024 * 1024;
    }

    @Override
    public StegoEmbedResult embed(byte[] cover, byte[] payload, EmbedOptions options) {
        byte[] container = PayloadCodec.encode(payload, options.key());
        String b64 = Base64.getEncoder().encodeToString(container);
        StringBuilder armor = new StringBuilder(BEGIN).append('\n');
        for (int i = 0; i < b64.length(); i += 76) {
            armor.append(b64, i, Math.min(b64.length(), i + 76)).append('\n');
        }
        armor.append(END);
        byte[] out;
        if (cover != null && cover.length > 0) {
            String coverText = new String(cover, StandardCharsets.UTF_8);
            out = (coverText.stripTrailing() + "\n\n" + armor + "\n").getBytes(StandardCharsets.UTF_8);
        } else {
            out = (armor + "\n").getBytes(StandardCharsets.UTF_8);
        }
        return new StegoEmbedResult(out, "text/plain", "stegohx_out.txt", payload.length, 0.0);
    }

    @Override
    public StegoExtractResult extract(byte[] data, String key, long seed) {
        if (data == null || data.length == 0) {
            throw new StegoException("empty input");
        }
        String text = new String(data, StandardCharsets.UTF_8);
        Matcher m = BLOCK.matcher(text);
        if (!m.find()) {
            throw new StegoException("no StegoHX armored block found in this text");
        }
        String b64 = m.group(1).replaceAll("\\s", "");
        byte[] container;
        try {
            container = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new StegoException("armored block is not valid Base64", e);
        }
        return new StegoExtractResult(PayloadCodec.decode(container, key), true);
    }

    @Override
    public CleanResult clean(byte[] data) {
        if (data == null || data.length == 0) {
            throw new StegoException("empty input");
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String cleaned = BLOCK.matcher(text).replaceAll("[stego block removed]").stripTrailing() + "\n";
        return new CleanResult(cleaned.getBytes(StandardCharsets.UTF_8), "text/plain",
                "replaced armored payload block(s) with a placeholder");
    }
}
