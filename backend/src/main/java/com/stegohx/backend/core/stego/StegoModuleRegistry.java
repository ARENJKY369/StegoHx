package com.stegohx.backend.core.stego;

import java.util.List;
import java.util.Optional;

import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.core.stego.audio.WavLsbModule;
import com.stegohx.backend.core.stego.image.ImageLsbModule;
import com.stegohx.backend.core.stego.network.NetworkCodecModule;
import com.stegohx.backend.core.stego.text.TextStegoModule;
import com.stegohx.backend.core.stego.video.Mp4AtomModule;

import org.springframework.stereotype.Component;

/**
 * Ordered module registry. Detection order matters: media-specific modules
 * first, generic fallbacks (text, network) last.
 */
@Component
public class StegoModuleRegistry {

    private final List<StegoModule> modules = List.of(
            new ImageLsbModule(),
            new WavLsbModule(),
            new Mp4AtomModule(),
            new NetworkCodecModule(),
            new TextStegoModule());

    public List<StegoModule> all() {
        return modules;
    }

    public Optional<StegoModule> byId(String id) {
        return modules.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** Auto-detect the module for the given bytes (or fall back to text). */
    public StegoModule detect(byte[] data, String filename) {
        for (StegoModule module : modules) {
            if (data != null && data.length > 0 && module.supports(data, filename)) {
                return module;
            }
        }
        if (data == null || data.length == 0) {
            return byId("text_armored").orElseThrow();
        }
        throw new StegoException("no stego module supports this file (" + filename + ")");
    }
}
