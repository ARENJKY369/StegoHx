# StegoHX Steganography Specification

This document specifies the on-disk/in-media formats StegoHX produces, so
that artifacts are fully self-describing and third-party tools could verify
or extract them.

## 1. Payload container (`STGX`)

Every payload — regardless of carrier — is wrapped first:

```
offset  size  field
0       4     magic            "STGX"
4       1     version          0x01
5       1     flags            0x00 (reserved)
6       4     payload length   big-endian, bytes
10      N     payload'         payload XOR keystream
```

**Keystream:** `HMAC-SHA256(key, counter)` blocks of 32 bytes, counter as
8-byte big-endian starting at 0. The key is the user passphrase (UTF-8);
an empty passphrase maps to the literal default `stegohx-default`.

> This is an obfuscation + keying layer, not authenticated encryption. If
> payload confidentiality is a hard requirement, encrypt before embedding.

## 2. LSB stream layout (image / audio modules)

Carriers are the RGB channel bytes (image) or the low bytes of 16-bit PCM
samples (audio). The stream uses 1–2 bits per carrier (`bit_depth`).

```
[ 96-bit header — always sequential ]
    bits  0–31   magic "STGX" (0x53544758)
    bits 32–63   container length in bytes
    bits 64–79   flags: bits 0-1 = bit_depth-1, bit 2 = seeded spread
    bits 80–95   reserved (zero)
[ container bits — sequential or seeded positions ]
```

- The header self-describes depth and placement, so **extraction only needs
  the key** (plus the explicit seed, if one was used instead of the
  key-derived default).
- Extraction probes depths 1 and 2; a wrong-depth probe yields a bad magic
  and moves on.
- **Seeded spread** maps stream index *i* to carrier
  `headerCarriers + perm(i, domain)` where `perm` is a 4-round Feistel
  bijection over the carrier domain (keyed by the seed, cycle-walked to the
  domain size). O(1) memory, O(1) per position — verified as an exact
  bijection for arbitrary domain sizes.
- Image capacity: `floor((3·W·H − 96/bit_depth) · bit_depth / 8)` bytes.
- Image covers are re-encoded as PNG (lossless). Embedding into a
  JPEG-decoded cover is allowed but the output is PNG, and the analyzer
  downweights spatial statistics on blocky (JPEG-like) covers.

## 3. Module specifics

### `image_lsb` — Image LSB
Carriers: R, G, B bytes of every pixel, raster order, channels interleaved.
Clean operation zeroes bit planes 0–1 of every channel.

### `audio_lsb_wav` — Audio LSB (PCM WAV)
RIFF is parsed and rewritten byte-for-byte except the `data` chunk; all other
chunks (metadata, cues) are preserved. Carriers are the low bytes of the
16-bit little-endian samples. Only PCM (audioFormat 1) at 16 bits is
accepted. Clean zeroes the two lowest bits of every sample.

### `video_mp4_atom` — ISO-BMFF container metadata
A single top-level atom is inserted directly after `ftyp`:

```
[ u32 BE size = 8 + N ][ 'stgx' ][ STGX container, N bytes ]
```

Per ISO/IEC 14496-12 demuxers skip unknown top-level atoms, so the file
stays playable and streams are untouched. Extraction locates the `stgx`
atom; clean removes all of them. 64-bit atom sizes are honored when parsing.

### `text_armored` — Armored text

```
-----BEGIN STEGOHX DATA-----
<Base64 of the STGX container, wrapped at 76 columns>
-----END STEGOHX DATA-----
```

Appended to cover text when a cover is provided. Clean replaces the block
with `[stego block removed]`.

### `network_transport` — Transport simulation (offline)

DNS variant (`variant=dns`):

```
# StegoHX simulated transport script (offline codec - no traffic generated)
# format: dns-query-stream
q1.<base32 chunk 1>.stgx.invalid
q2.<base32 chunk 2>.stgx.invalid
…
```

HTTP variant (`variant=http`): `X-Session-Fragment-<i>: <chunk>` lines.

Chunks are 56-char RFC-4648 Base32 fragments of the STGX container. This is
a **codec for detection training** — it produces the *shape* of covert-channel
traffic so defenders can build parsers and corpora. No sockets, no traffic.

## 4. The Evolve algorithm (adaptive embedding)

Given (cover, payload, key, module):

```
baseline   = analyze(cover).threat_score
strategies = image/audio: {lsb1-sequential, lsb1-seeded, lsb2-sequential, lsb2-seeded}
             containers: {container-fixed}
for s in strategies:
    stego      = module.embed(cover, payload, s)      # in memory
    c.score    = analyze(stego).threat_score          # full analyzer roundtrip
selected  = argmin(c.score for c in candidates if c.ok)
```

Selection is minimum analyzer threat score (ties resolved by evaluation
order — lower bit depth first, i.e. least distortion). The response includes
every candidate, the baseline, and an advice string that interprets the
delta:

- delta ≈ 0 → embedding is indistinguishable to the analyzer
- delta < 0.10 → mild footprint
- delta ≥ 0.10 → readily detectable; a good positive pair for detector
  training

This is the standard red/blue-team iteration loop: measure detectability of
your embedding choices, then harden the detector until the delta shrinks.

## 5. Analyzer science notes

- **RS analysis** implements the closed-form estimator from Fridrich, Goljan
  & Du (2001): measure `R_M, S_M, R_-M, S_-M` at `p/2` (as-is) and `1−p/2`
  (all-LSBs-flipped), form the differences `d0, d1, d−0, d−1`, solve
  `2(d1+d0)z² + (d−0 − d−1 − d1 − 3d0)z + (d0 − d−0) = 0` and map back with
  `p = z/(z − ½)` using the smaller-|z| root. Negative estimates are the
  method's documented clean-image bias.
- **Chi-square PoV** follows Westfeld & Pfitzmann with the p-value computed
  as `1 − CDF` (Wilson–Hilferty approximation), evaluated over raster
  prefixes {12.5%, 25%, 50%, 100%} to expose sequential embedding.
- **Audio**: LSB statistics are only meaningful in low-activity segments
  (consecutive sample steps ≤ 3); the analyzer selects those segments and
  runs RS + chi-square there, reporting reduced sensitivity when a recording
  has none.
- **JPEG covers:** an 8×8 blockiness ratio ≥ 1.5 halves the weight of the
  spatial analyzers (decompressed-JPEG covers violate their assumptions).

Self-calibration curves and thresholds live in `ml-service/app/analyzers/`
and are tuned so that the synthetic corpus in `samples/` self-tests green:
clean covers score CLEAN, 30%-embedded covers score LIKELY/HIGH confidence.
