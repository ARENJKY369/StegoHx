package com.stegohx.backend.api.dto;

import java.util.List;

/** DTOs for the adaptive embedding ("evolve") operation. */
public final class EvolveDtos {

    private EvolveDtos() {
    }

    /**
     * @param baselineThreatScore analyzer score of the original cover
     * @param selected            chosen strategy (null when every candidate failed)
     * @param candidates          all evaluated candidates, in evaluation order
     * @param advice              human-readable summary of the decision
     */
    public record EvolveResponse(double baselineThreatScore, Candidate selected,
            List<Candidate> candidates, String advice) {
    }

    public record Strategy(String label, int bitDepth, String spread) {
    }

    /**
     * @param strategy     the strategy evaluated
     * @param ok           whether embedding and analysis succeeded
     * @param threatScore  analyzer score of the resulting stego object
     * @param payloadBytes embedded payload size
     * @param capacityUsed fraction of the carrier capacity used
     * @param error        failure reason when {@code ok} is false
     */
    public record Candidate(Strategy strategy, boolean ok, double threatScore, long payloadBytes,
            double capacityUsed, String error) {
    }
}
