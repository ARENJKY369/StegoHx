package com.stegohx.backend.api.controller;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.stegohx.backend.api.dto.EvolveDtos.EvolveResponse;
import com.stegohx.backend.api.dto.StegoDtos.CleanResponse;
import com.stegohx.backend.api.dto.StegoDtos.ExtractResponse;
import com.stegohx.backend.api.dto.StegoDtos.HideResponse;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.Spread;
import com.stegohx.backend.core.stego.StegoModule;
import com.stegohx.backend.core.stego.StegoModuleRegistry;
import com.stegohx.backend.service.EvolveService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stego operations: hide / extract / clean / evolve.
 *
 * All operations are synchronous, in-memory and local. Files are never
 * retained by the engine (only scan metadata is persisted, without file
 * content).
 */
@RestController
@RequestMapping("/api/v1/stego")
public class StegoController {

    private final StegoModuleRegistry registry;
    private final EvolveService evolveService;

    public StegoController(StegoModuleRegistry registry, EvolveService evolveService) {
        this.registry = registry;
        this.evolveService = evolveService;
    }

    @PostMapping(value = "/hide", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HideResponse hide(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam("payload") String payload,
            @RequestParam(value = "key", required = false, defaultValue = "") String key,
            @RequestParam(value = "module_id", required = false) String moduleId,
            @RequestParam(value = "bit_depth", required = false, defaultValue = "1") int bitDepth,
            @RequestParam(value = "spread", required = false, defaultValue = "SEQUENTIAL") Spread spread,
            @RequestParam(value = "seed", required = false, defaultValue = "0") long seed,
            @RequestParam(value = "variant", required = false, defaultValue = "dns") String variant)
            throws Exception {

        byte[] cover = file == null || file.isEmpty() ? new byte[0] : file.getBytes();
        String filename = file == null || file.isEmpty() ? "payload-only" : file.getOriginalFilename();
        StegoModule module = moduleId == null || moduleId.isBlank()
                ? registry.detect(cover.length == 0 ? null : cover, filename)
                : registry.byId(moduleId).orElseThrow(() -> new IllegalArgumentException("unknown module_id: " + moduleId));
        EmbedOptions options = new EmbedOptions(key, bitDepth, spread, seed, variant);
        StegoModule.StegoEmbedResult result = module.embed(cover, payload.getBytes(StandardCharsets.UTF_8), options);
        return new HideResponse(module.id(), module.displayName(), result.outputName(), result.outputMime(),
                Base64.getEncoder().encodeToString(result.output()), result.payloadBytes(),
                result.capacityUsedRatio());
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractResponse extract(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "key", required = false, defaultValue = "") String key,
            @RequestParam(value = "module_id", required = false) String moduleId,
            @RequestParam(value = "seed", required = false, defaultValue = "0") long seed)
            throws Exception {

        byte[] data = file.getBytes();
        String filename = file.getOriginalFilename();
        StegoModule module = moduleId == null || moduleId.isBlank()
                ? registry.detect(data, filename)
                : registry.byId(moduleId).orElseThrow(() -> new IllegalArgumentException("unknown module_id: " + moduleId));
        StegoModule.StegoExtractResult result = module.extract(data, key, seed);
        boolean printable = isMostlyPrintable(result.payload());
        return new ExtractResponse(module.id(), module.displayName(),
                printable ? new String(result.payload(), StandardCharsets.UTF_8) : null,
                Base64.getEncoder().encodeToString(result.payload()),
                result.payload().length,
                result.containerVerified());
    }

    @PostMapping(value = "/clean", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CleanResponse clean(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "module_id", required = false) String moduleId) throws Exception {

        byte[] data = file.getBytes();
        String filename = file.getOriginalFilename();
        StegoModule module = moduleId == null || moduleId.isBlank()
                ? registry.detect(data, filename)
                : registry.byId(moduleId).orElseThrow(() -> new IllegalArgumentException("unknown module_id: " + moduleId));
        StegoModule.CleanResult result = module.clean(data);
        return new CleanResponse(module.id(), module.displayName(), result.outputMime(),
                Base64.getEncoder().encodeToString(result.output()), result.note());
    }

    @PostMapping(value = "/evolve", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EvolveResponse evolve(
            @RequestPart("file") MultipartFile file,
            @RequestParam("payload") String payload,
            @RequestParam(value = "key", required = false, defaultValue = "") String key,
            @RequestParam(value = "module_id", required = false) String moduleId) throws Exception {

        return evolveService.evolve(file.getBytes(), file.getOriginalFilename(),
                payload.getBytes(StandardCharsets.UTF_8), key, moduleId);
    }

    private static boolean isMostlyPrintable(byte[] data) {
        if (data.length == 0) {
            return true;
        }
        int printable = 0;
        int check = Math.min(data.length, 4096);
        for (int i = 0; i < check; i++) {
            byte b = data[i];
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b < 127)) {
                printable++;
            }
        }
        return printable * 10 >= check * 9; // >= 90% printable
    }
}
