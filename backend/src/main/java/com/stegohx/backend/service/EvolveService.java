package com.stegohx.backend.service;

import java.util.ArrayList;
import java.util.List;

import com.stegohx.backend.api.dto.AnalyzerDtos.AnalysisResponse;
import com.stegohx.backend.api.dto.EvolveDtos.Candidate;
import com.stegohx.backend.api.dto.EvolveDtos.EvolveResponse;
import com.stegohx.backend.api.dto.EvolveDtos.Strategy;
import com.stegohx.backend.core.stego.EmbedOptions;
import com.stegohx.backend.core.stego.MediaKind;
import com.stegohx.backend.core.stego.Spread;
import com.stegohx.backend.core.stego.StegoModule;
import com.stegohx.backend.core.stego.StegoModuleRegistry;
import com.stegohx.backend.core.stego.StegoModule.StegoEmbedResult;

import org.springframework.stereotype.Service;

/**
 * Adaptive embedding ("evolve").
 *
 * The engine embeds the payload under each candidate strategy, asks the
 * analyzer service to score the result, and selects the strategy with the
 * lowest threat score. This feedback loop is the core of the research
 * workflow: it quantifies how embedding parameters affect detectability of
 * YOUR OWN detectors, which is exactly how detector hardening is evaluated
 * (red-team/blue-team iteration). All scoring happens against the local
 * analyzer service - nothing leaves the deployment.
 *
 * Strategy space (LSB modules): bit depth {1, 2} x placement {sequential,
 * seeded}. Container modules (MP4 atom, text, network) have a single fixed
 * strategy, which is still scored so the response shape stays uniform.
 */
@Service
public class EvolveService {

    private static final List<Strategy> LSB_STRATEGIES = List.of(
            new Strategy("lsb1-sequential", 1, Spread.SEQUENTIAL.name()),
            new Strategy("lsb1-seeded", 1, Spread.SEEDED.name()),
            new Strategy("lsb2-sequential", 2, Spread.SEQUENTIAL.name()),
            new Strategy("lsb2-seeded", 2, Spread.SEEDED.name()));

    private final StegoModuleRegistry registry;
    private final AnalyzerClient analyzerClient;

    public EvolveService(StegoModuleRegistry registry, AnalyzerClient analyzerClient) {
        this.registry = registry;
        this.analyzerClient = analyzerClient;
    }

    public EvolveResponse evolve(byte[] cover, String filename, byte[] payload, String key, String moduleId) {
        StegoModule module = moduleId == null || moduleId.isBlank()
                ? registry.detect(cover, filename)
                : registry.byId(moduleId).orElseThrow(() -> new IllegalArgumentException(
                        "unknown module id: " + moduleId));

        double baseline = analyzerClient.analyze(cover, filename).threatScore();

        List<Strategy> strategies = strategiesFor(module);
        List<Candidate> candidates = new ArrayList<>();
        Candidate best = null;
        for (Strategy strategy : strategies) {
            Candidate candidate = evaluate(module, cover, filename, payload, key, strategy);
            candidates.add(candidate);
            if (candidate.ok() && (best == null || candidate.threatScore() < best.threatScore())) {
                best = candidate;
            }
        }
        String advice = advice(best, baseline);
        return new EvolveResponse(baseline, best, candidates, advice);
    }

    private Candidate evaluate(StegoModule module, byte[] cover, String filename, byte[] payload,
            String key, Strategy strategy) {
        try {
            EmbedOptions options = new EmbedOptions(key, strategy.bitDepth(),
                    Spread.valueOf(strategy.spread()), 0L, "dns");
            StegoEmbedResult embedded = module.embed(cover, payload, options);
            AnalysisResponse analysis = analyzerClient.analyze(embedded.output(), filename);
            return new Candidate(strategy, true, analysis.threatScore(), payload.length,
                    embedded.capacityUsedRatio(), null);
        } catch (Exception e) {
            return new Candidate(strategy, false, 1.0, payload.length, 0.0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static List<Strategy> strategiesFor(StegoModule module) {
        MediaKind kind = module.mediaKind();
        if (kind == MediaKind.IMAGE || kind == MediaKind.AUDIO) {
            return LSB_STRATEGIES;
        }
        return List.of(new Strategy("container-fixed", 1, Spread.SEQUENTIAL.name()));
    }

    private static String advice(Candidate best, double baseline) {
        if (best == null) {
            return "No candidate strategy could embed this payload; reduce the payload size or use a larger cover.";
        }
        double delta = best.threatScore() - baseline;
        if (delta <= 0.001) {
            return String.format(
                    "Strategy '%s' stays at the cover's baseline detectability (%.3f). "
                            + "The analyzer does not separate this embedding from the original cover.",
                    best.strategy().label(), best.threatScore());
        }
        if (delta < 0.10) {
            return String.format(
                    "Strategy '%s' scores %.3f vs cover baseline %.3f (+%.3f): mild statistical footprint. "
                            + "Consider a larger cover or shorter payload.",
                    best.strategy().label(), best.threatScore(), baseline, delta);
        }
        return String.format(
                "Strategy '%s' scores %.3f vs cover baseline %.3f (+%.3f): every evaluated strategy is "
                        + "readily detectable by the analyzer. For detector-hardening research this cover/payload "
                        + "combination is a good positive training pair.",
                best.strategy().label(), best.threatScore(), baseline, delta);
    }
}
