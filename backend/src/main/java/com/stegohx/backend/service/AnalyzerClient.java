package com.stegohx.backend.service;

import com.stegohx.backend.api.dto.AnalyzerDtos.AnalysisResponse;
import com.stegohx.backend.api.dto.AnalyzerDtos.AnalyzerHealth;
import com.stegohx.backend.core.StegoException;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Typed client for the Python analyzer service.
 */
@Component
public class AnalyzerClient {

    private final RestClient restClient;

    public AnalyzerClient(RestClient analyzerRestClient) {
        this.restClient = analyzerRestClient;
    }

    /** Analyze a file, mapping transport errors to domain exceptions. */
    public AnalysisResponse analyze(byte[] data, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        try {
            AnalysisResponse response = restClient.post()
                    .uri("/api/v1/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(AnalysisResponse.class);
            if (response == null) {
                throw new StegoException("analyzer service returned an empty response");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new StegoException("analyzer service unreachable at "
                    + e.getMessage().replaceFirst("^.*?\\[", "["), e);
        } catch (RestClientResponseException e) {
            throw new StegoException("analyzer service rejected the file (HTTP "
                    + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), e);
        }
    }

    /** Health probe; returns null when the analyzer is unreachable. */
    public AnalyzerHealth health() {
        try {
            return restClient.get().uri("/health").retrieve().body(AnalyzerHealth.class);
        } catch (Exception e) {
            return null;
        }
    }
}
