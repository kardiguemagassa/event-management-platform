package com.kmg.eventplatform.registration.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared handling of downstream calls and error responses (RFC 9457 ProblemDetail with a stable "code").
 */
final class DownstreamResponses {

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);

    private DownstreamResponses() {
    }

    /**
     * Every way a call can fail without an HTTP answer becomes a {@link DownstreamUnavailableException}:
     * I/O errors and timeouts (RestClientException), and Spring Cloud LoadBalancer finding no instance at all
     * (IllegalStateException "No instances available for ...").
     * Fast, retryable failures are marked transient, a missing instance as {@link NoInstanceAvailableException}.
     */
    static <T> T call(String service, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException | IllegalStateException noAnswer) {
            throw new DownstreamUnavailableException(service, classify(noAnswer));
        }
    }

    static String problemCode(ConvertibleClientHttpResponse response) throws IOException {
        ProblemBody body = response.bodyTo(ProblemBody.class);
        return body == null || body.code() == null ? "" : body.code();
    }

    /**
     * 5xx means the downstream is failing: unavailable, and transient for 502/503/504.
     * Anything else breaks the contract: a bug, not an outage.
     */
    static RuntimeException unexpected(String service, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            String detail = "responded " + status.value();
            return TRANSIENT_STATUSES.contains(status.value())
                    ? new DownstreamUnavailableException(service, new TransientDownstreamException(detail))
                    : new DownstreamUnavailableException(service, detail);
        }
        return new DownstreamContractException(service + " responded with unexpected status " + status.value());
    }

    /**
     * No registered instance, or connection refused: nothing was processed and another try is cheap.
     * Anything else (a timeout, for instance) is kept as is: not transient.
     */
    private static Throwable classify(RuntimeException noAnswer) {
        if (noAnswer instanceof IllegalStateException) {
            return new NoInstanceAvailableException(noAnswer);
        }
        for (Throwable cause = noAnswer; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return new TransientDownstreamException(noAnswer);
            }
        }
        return noAnswer;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemBody(String code) {
    }
}
