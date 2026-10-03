package com.stegohx.backend.core.stego.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.MediaKind;
import com.stegohx.backend.core.stego.StegoModule;
import com.stegohx.backend.core.stego.lsb.BitCarrier;
import com.stegohx.backend.core.stego.lsb.LsbEmbedder;

/**
 * Image LSB module: payload bits replace the least significant bit planes of
 * the RGB channels. Works on any format ImageIO can decode; output is always
 * re-encoded as PNG (lossless) so the embedded bits survive.
 */
public class ImageLsbModule implements StegoModule {

    @Override
    public String id() {
        return "image_lsb";
    }

    @Override
    public String displayName() {
        return "Image LSB (RGB bit planes)";
    }

    @Override
    public String description() {
        return "Replaces the lowest 1-2 bit planes of RGB pixel channels with keyed, "
                + "optionally scattered payload bits. Covers are re-encoded as lossless PNG.";
    }

    @Override
    public MediaKind mediaKind() {
        return MediaKind.IMAGE;
    }

    @Override
    public boolean supports(byte[] data, String filename) {
        if (data == null || data.length < 8) {
            return false;
        }
        boolean png = (data[0] & 0xFF) == 0x89 && data[1] == 'P';
        boolean bmp = data[0] == 'B' && data[1] == 'M';
        boolean gif = new String(data, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).startsWith("GIF");
        boolean jpeg = (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
        boolean nameMatch = filename != null
                && Arrays.stream(new String[]{".png", ".bmp", ".gif", ".jpg", ".jpeg"})
                        .anyMatch(s -> filename.toLowerCase(Locale.ROOT).endsWith(s));
        return png || bmp || gif || jpeg || nameMatch;
    }

    @Override
    public long capacityBytes(byte[] data, EmbedOptions options) {
        DecodedImage img = decode(data);
        return LsbEmbedder.capacityBytes(new PixelCarrier(img.pixels()), options.bitDepth());
    }

    @Override
    public StegoEmbedResult embed(byte[] cover, byte[] payload, EmbedOptions options) {
        DecodedImage img = decode(cover);
        PixelCarrier carrier = new PixelCarrier(img.pixels());
        LsbEmbedder.embed(carrier, payload, options);
        byte[] out = encodePng(carrier.pixels(), img.width(), img.height());
        long capacity = LsbEmbedder.capacityBytes(new PixelCarrier(img.pixels()), options.bitDepth());
        double used = payload.length == 0 ? 0.0 : (double) payload.length / Math.max(1, capacity);
        return new StegoEmbedResult(out, "image/png", "stegohx_out.png", payload.length, Math.min(1.0, used));
    }

    @Override
    public StegoExtractResult extract(byte[] data, String key, long seed) {
        DecodedImage img = decode(data);
        byte[] payload = LsbEmbedder.extract(new PixelCarrier(img.pixels()),
                new LsbEmbedder.ExtractOptions(key, seed));
        return new StegoExtractResult(payload, true);
    }

    @Override
    public CleanResult clean(byte[] data) {
        DecodedImage img = decode(data);
        PixelCarrier carrier = new PixelCarrier(img.pixels());
        LsbEmbedder.scrub(carrier, 2); // zero bit planes 0 and 1 of every channel
        byte[] out = encodePng(carrier.pixels(), img.width(), img.height());
        return new CleanResult(out, "image/png",
                "zeroed the two lowest bit planes of every RGB channel; any 1-2 bit LSB payload is destroyed");
    }

    // --- pixel plumbing ------------------------------------------------------

    private record DecodedImage(int[] pixels, int width, int height) {
    }

    private static DecodedImage decode(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                throw new StegoException("image could not be decoded (unsupported format?)");
            }
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(img, 0, 0, null);
            int[] pixels = rgb.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
            return new DecodedImage(pixels, img.getWidth(), img.getHeight());
        } catch (StegoException e) {
            throw e;
        } catch (Exception e) {
            throw new StegoException("image decode failed: " + e.getMessage(), e);
        }
    }

    private static byte[] encodePng(int[] pixels, int width, int height) {
        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, width, height, pixels, 0, width);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new StegoException("PNG encode failed: " + e.getMessage(), e);
        }
    }

    /** Carrier over packed RGB pixels; channels ordered R, G, B. */
    static final class PixelCarrier implements BitCarrier {

        private final int[] pixels;

        PixelCarrier(int[] pixels) {
            this.pixels = pixels;
        }

        @Override
        public int carrierCount() {
            return pixels.length * 3;
        }

        @Override
        public int getBits(int index) {
            int pixel = pixels[index / 3];
            int channel = index % 3;
            return (pixel >>> (channel * 8)) & 0xFF;
        }

        @Override
        public void setBits(int index, int value) {
            int p = index / 3;
            int channel = index % 3;
            int shift = channel * 8;
            int cleared = pixels[p] & ~(0xFF << shift);
            pixels[p] = cleared | ((value & 0xFF) << shift);
        }
    }
}
