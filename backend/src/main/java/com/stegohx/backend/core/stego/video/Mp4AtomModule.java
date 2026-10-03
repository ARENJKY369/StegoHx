package com.stegohx.backend.core.stego.video;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.codec.PayloadCodec;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.MediaKind;
import com.stegohx.backend.core.stego.StegoModule;

/**
 * Video container module (ISO-BMFF: MP4 / MOV / M4A).
 *
 * Payloads are stored in a dedicated top-level {@code stgx} atom inserted
 * after {@code ftyp}. Demuxers skip unknown top-level atoms per the
 * ISO-BMFF specification, so the file remains fully playable. This is
 * container-level (metadata) embedding: the audio/video streams themselves
 * are never touched.
 */
public class Mp4AtomModule implements StegoModule {

    private static final byte[] ATOM_ID = {'s', 't', 'g', 'x'};

    @Override
    public String id() {
        return "video_mp4_atom";
    }

    @Override
    public String displayName() {
        return "Video Container Metadata (MP4/MOV)";
    }

    @Override
    public String description() {
        return "Stores the keyed payload in a dedicated top-level 'stgx' atom. Players skip "
                + "unknown atoms, so the media stays playable; stream data is untouched.";
    }

    @Override
    public MediaKind mediaKind() {
        return MediaKind.VIDEO;
    }

    @Override
    public boolean supports(byte[] data, String filename) {
        if (data != null && data.length >= 12) {
            if (new String(data, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) {
                return true;
            }
        }
        return filename != null && Arrays.asList(".mp4", ".mov", ".m4a").stream()
                .anyMatch(s -> filename.toLowerCase(Locale.ROOT).endsWith(s));
    }

    @Override
    public long capacityBytes(byte[] data, EmbedOptions options) {
        return 8L * 1024 * 1024; // practical cap, container-level storage
    }

    @Override
    public StegoEmbedResult embed(byte[] cover, byte[] payload, EmbedOptions options) {
        List<Atom> atoms = parseTopLevel(cover);
        byte[] container = PayloadCodec.encode(payload, options.key());
        byte[] atom = buildAtom(container);
        ByteArrayOutputStream out = new ByteArrayOutputStream(cover.length + atom.length);
        boolean inserted = false;
        for (Atom a : atoms) {
            out.write(cover, a.offset(), a.size());
            if (!inserted && a.fourcc().equals("ftyp")) {
                out.writeBytes(atom);
                inserted = true;
            }
        }
        if (!inserted) {
            // no ftyp (rare): append the payload atom at the end instead
            out.writeBytes(atom);
        }
        double used = (double) payload.length / Math.max(1, capacityBytes(cover, options));
        return new StegoEmbedResult(out.toByteArray(), "video/mp4", "stegohx_out.mp4", payload.length,
                Math.min(1.0, used));
    }

    @Override
    public StegoExtractResult extract(byte[] data, String key, long seed) {
        for (Atom a : parseTopLevel(data)) {
            if (a.fourcc().equals("stgx")) {
                byte[] container = new byte[a.size() - 8];
                System.arraycopy(data, a.offset() + 8, container, 0, container.length);
                return new StegoExtractResult(PayloadCodec.decode(container, key), true);
            }
        }
        throw new StegoException("no 'stgx' atom found in this container");
    }

    @Override
    public CleanResult clean(byte[] data) {
        List<Atom> atoms = parseTopLevel(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        boolean removed = false;
        for (Atom a : atoms) {
            if (a.fourcc().equals("stgx")) {
                removed = true;
                continue;
            }
            out.write(data, a.offset(), a.size());
        }
        if (!removed) {
            return new CleanResult(data, "video/mp4", "no 'stgx' atom present; file returned unchanged");
        }
        return new CleanResult(out.toByteArray(), "video/mp4", "removed all 'stgx' payload atoms");
    }

    // --- atom plumbing -------------------------------------------------------

    private record Atom(String fourcc, int offset, int size) {
    }

    private static List<Atom> parseTopLevel(byte[] data) {
        List<Atom> atoms = new ArrayList<>();
        int offset = 0;
        while (offset + 8 <= data.length && atoms.size() < 256) {
            int size = readIntBE(data, offset);
            String fourcc = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
            int header = 8;
            if (size == 1) { // 64-bit extended size
                if (offset + 16 > data.length) {
                    break;
                }
                long ext = readLongBE(data, offset + 8);
                if (ext < 16 || ext > data.length - offset) {
                    break;
                }
                size = (int) ext;
                header = 16;
            } else if (size == 0) { // to end of file
                size = data.length - offset;
            }
            if (size < header || offset + size > data.length) {
                break; // malformed tail - stop walking
            }
            atoms.add(new Atom(fourcc, offset, size));
            offset += size;
        }
        if (atoms.isEmpty()) {
            throw new StegoException("no ISO-BMFF top-level atoms found (not an MP4/MOV container?)");
        }
        return atoms;
    }

    private static byte[] buildAtom(byte[] payload) {
        int size = 8 + payload.length;
        byte[] atom = new byte[size];
        atom[0] = (byte) (size >>> 24);
        atom[1] = (byte) (size >>> 16);
        atom[2] = (byte) (size >>> 8);
        atom[3] = (byte) size;
        System.arraycopy(ATOM_ID, 0, atom, 4, 4);
        System.arraycopy(payload, 0, atom, 8, payload.length);
        return atom;
    }

    private static int readIntBE(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16) | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static long readLongBE(byte[] d, int off) {
        return ((long) readIntBE(d, off) << 32) | (readIntBE(d, off + 4) & 0xFFFFFFFFL);
    }
}
