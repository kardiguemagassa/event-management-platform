package com.kmg.eventplatform.registration.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Shared handling of downstream calls and error responses (RFC 9457 ProblemDetail with a stable "code").
 */
final class DownstreamResponses {

    private DownstreamResponses() {
    }

    /**
     * Every way a call can fail without an HTTP answer becomes a retryable {@link DownstreamUnavailableException}:
     * I/O errors and timeouts (RestClientException), and Spring Cloud LoadBalancer finding no instance at all
     * (IllegalStateException "No instances available for ...").
     */
    static <T> T call(String service, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException | IllegalStateException noAnswer) {
            throw new DownstreamUnavailableException(service, noAnswer);
        }
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
        return new DownstreamContractException(service + " responded with unexpected status " + status.value());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemBody(String code) {
    }
}
