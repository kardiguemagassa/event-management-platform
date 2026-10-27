package com.kmg.eventplatform.registration.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import java.io.IOException;

/**
 * Shared reading of downstream error responses (RFC 9457 ProblemDetail with a stable "code").
 */
final class DownstreamResponses {

    private DownstreamResponses() {
    }

    static String problemCode(ConvertibleClientHttpResponse response) throws IOException {
        ProblemBody body = response.bodyTo(ProblemBody.class);
        return body == null || body.code() == null ? "" : body.code();
    }

    /**
     * 5xx means the downstream is failing: retryable. Anything else breaks the contract: a bug, not an outage.
     */
    static RuntimeException unexpected(String service, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return new DownstreamUnavailableException(service, "responded " + status.value());
        }
        return new IllegalStateException(service + " responded with unexpected status " + status.value());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemBody(String code) {
    }
}
