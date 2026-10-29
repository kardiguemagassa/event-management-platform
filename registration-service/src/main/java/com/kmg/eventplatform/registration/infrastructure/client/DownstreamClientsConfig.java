package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventCatalog;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DownstreamClientsProperties.class)
class DownstreamClientsConfig {

    /**
     * Plain builder for unqualified injection points: the Eureka client itself picks up any
     * RestClient.Builder bean and must never resolve its own server URL through the load balancer.
     */
    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder restClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    /**
     * Resolves service ids through Spring Cloud LoadBalancer, with fail-fast timeouts.
     */
    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer,
                                                     DownstreamClientsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return configurer.configure(RestClient.builder()).requestFactory(requestFactory);
    }

    /** Breaker settings from our own validated properties, through the Spring Cloud API. */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> downstreamCircuitBreakers(DownstreamClientsProperties properties) {
        CircuitBreakerConfig config = circuitBreakerConfig(properties.circuitBreaker());
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(config)
                .build());
    }

    /**
     * Only {@link DownstreamUnavailableException} counts as a failure, except a missing instance
     * ({@link NoInstanceAvailableException}): ignored, it neither opens the circuit nor uses a half-open probe.
     * A half-open circuit that cannot conclude goes back to open after maxWaitDurationInHalfOpenState.
     */
    static CircuitBreakerConfig circuitBreakerConfig(DownstreamClientsProperties.CircuitBreaker settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedNumberOfCallsInHalfOpenState())
                .maxWaitDurationInHalfOpenState(settings.maxWaitDurationInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(DownstreamUnavailableException.class)
                .ignoreException(NoInstanceAvailableException::isCauseOf)
                .build();
    }

    @Bean
    DownstreamResilience downstreamResilience(CircuitBreakerFactory<?, ?> circuitBreakers,
                                              DownstreamClientsProperties properties) {
        DownstreamClientsProperties.Retry settings = properties.retry();
        RetryPolicy policy = RetryPolicy.builder()
                .includes(TransientDownstreamException.class)
                .maxRetries(settings.maxRetries())
                .delay(settings.delay())
                .jitter(settings.jitter())
                .build();
        return new DownstreamResilience(circuitBreakers, new RetryTemplate(policy));
    }

    /** Calls to event-service carry the service account token (capacity routes need SEAT_MANAGER). */
    @Bean
    EventCatalog eventCatalog(@LoadBalanced RestClient.Builder loadBalanced, DownstreamClientsProperties properties,
                              DownstreamResilience resilience, ServiceAccountTokenInterceptor serviceAccount) {
        RestClient client = loadBalanced.clone()
                .baseUrl(properties.eventServiceUrl())
                .requestInterceptor(serviceAccount)
                .build();
        return new ResilientEventCatalog(new RestEventCatalog(client), resilience);
    }
}
