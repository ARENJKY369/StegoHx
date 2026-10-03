package com.stegohx.backend.core.stego.audio;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.MediaKind;
import com.stegohx.backend.core.stego.StegoModule;
import com.stegohx.backend.core.stego.lsb.BitCarrier;
import com.stegohx.backend.core.stego.lsb.LsbEmbedder;

/**
 * Audio LSB module for 16-bit PCM WAV: payload bits replace the least
 * significant bit(s) of each sample. The RIFF container is parsed and
 * rewritten manually so all non-data chunks (metadata, cues) are preserved.
 */
public class WavLsbModule implements StegoModule {

    @Override
    public String id() {
        return "audio_lsb_wav";
    }

    @Override
    public String displayName() {
        return "Audio LSB (PCM WAV)";
    }

    @Override
    public String description() {
        return "Replaces the lowest 1-2 bits of every 16-bit PCM sample with keyed payload bits. "
                + "Only lossless WAV is supported (MP3/AAC are not bit-exact).";
    }

    @Override
    public MediaKind mediaKind() {
        return MediaKind.AUDIO;
    }

    @Override
    public boolean supports(byte[] data, String filename) {
        if (data == null || data.length < 12) {
            return false;
        }
        boolean riffWave = new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
        boolean nameMatch = filename != null
                && (filename.toLowerCase(Locale.ROOT).endsWith(".wav")
                        || filename.toLowerCase(Locale.ROOT).endsWith(".wave"));
        return riffWave || nameMatch;
    }

    @Override
    public long capacityBytes(byte[] data, EmbedOptions options) {
        WavInfo info = parse(data);
        return LsbEmbedder.capacityBytes(new SampleCarrier(data, info.dataOffset(), info.sampleCount()),
                options.bitDepth());
    }

    @Override
    public StegoEmbedResult embed(byte[] cover, byte[] payload, EmbedOptions options) {
        WavInfo info = parse(cover);
        byte[] out = cover.clone();
        SampleCarrier carrier = new SampleCarrier(out, info.dataOffset(), info.sampleCount());
        LsbEmbedder.embed(carrier, payload, options);
        long capacity = LsbEmbedder.capacityBytes(carrier, options.bitDepth());
        double used = payload.length == 0 ? 0.0 : (double) payload.length / Math.max(1, capacity);
        return new StegoEmbedResult(out, "audio/wav", "stegohx_out.wav", payload.length, Math.min(1.0, used));
    }

    @Override
    public StegoExtractResult extract(byte[] data, String key, long seed) {
        WavInfo info = parse(data);
        SampleCarrier carrier = new SampleCarrier(data, info.dataOffset(), info.sampleCount());
        byte[] payload = LsbEmbedder.extract(carrier, new LsbEmbedder.ExtractOptions(key, seed));
        return new StegoExtractResult(payload, true);
    }

    @Override
    public CleanResult clean(byte[] data) {
        WavInfo info = parse(data);
        byte[] out = data.clone();
        SampleCarrier carrier = new SampleCarrier(out, info.dataOffset(), info.sampleCount());
        LsbEmbedder.scrub(carrier, 2);
        return new CleanResult(out, "audio/wav",
                "zeroed the two lowest bits of every PCM sample; any 1-2 bit LSB payload is destroyed");
    }

    // --- RIFF plumbing -------------------------------------------------------

    private record WavInfo(int dataOffset, int dataLen, int sampleCount, int channels, int sampleRate) {
    }

    private static WavInfo parse(byte[] data) {
        try {
            if (data.length < 44 || !new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    || !new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
                throw new StegoException("not a RIFF/WAVE file");
            }
            int offset = 12;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            int audioFormat = 0;
            int dataOffset = -1;
            int dataLen = 0;
            while (offset + 8 <= data.length) {
                String chunkId = new String(data, offset, 4, StandardCharsets.US_ASCII);
                int chunkSize = readIntLE(data, offset + 4);
                int body = offset + 8;
                if (chunkId.equals("fmt ")) {
                    if (chunkSize < 16 || body + 16 > data.length) {
                        throw new StegoException("malformed fmt chunk");
                    }
                    audioFormat = readShortLE(data, body);
                    channels = readShortLE(data, body + 2);
                    sampleRate = readIntLE(data, body + 4);
                    bitsPerSample = readShortLE(data, body + 14);
                } else if (chunkId.equals("data")) {
                    dataOffset = body;
                    dataLen = Math.min(chunkSize, data.length - body);
                }
                // chunks are word-aligned
                offset = body + chunkSize + (chunkSize & 1);
            }
            if (audioFormat != 1 || bitsPerSample != 16) {
                throw new StegoException("only 16-bit PCM WAV is supported (got format=" + audioFormat
                        + ", bits=" + bitsPerSample + ")");
            }
            if (dataOffset < 0 || dataLen < 8) {
                throw new StegoException("no PCM data chunk found");
            }
            int sampleCount = dataLen / 2;
            if (sampleCount < 128) {
                throw new StegoException("audio too short to carry a payload");
            }
            return new WavInfo(dataOffset, dataLen, sampleCount, channels, sampleRate);
        } catch (StegoException e) {
            throw e;
        } catch (Exception e) {
            throw new StegoException("WAV parse failed: " + e.getMessage(), e);
        }
    }

    private static int readIntLE(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8) | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    /** Carrier over the low byte of each 16-bit little-endian sample. */
    static final class SampleCarrier implements BitCarrier {

        private final byte[] data;
        private final int dataOffset;
        private final int sampleCount;

        SampleCarrier(byte[] data, int dataOffset, int sampleCount) {
            this.data = data;
            this.dataOffset = dataOffset;
            this.sampleCount = sampleCount;
        }

        @Override
        public int carrierCount() {
            return sampleCount;
        }

        @Override
        public int getBits(int index) {
            return data[dataOffset + 2 * index] & 0xFF; // low byte of LE int16
        }

        @Override
        public void setBits(int index, int value) {
            data[dataOffset + 2 * index] = (byte) value; // high byte preserved
        }
    }
}
