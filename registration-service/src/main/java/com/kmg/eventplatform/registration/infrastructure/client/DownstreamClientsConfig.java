package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.EventCatalog;
import com.kmg.eventplatform.registration.application.UserDirectory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
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

    @Bean
    UserDirectory userDirectory(@LoadBalanced RestClient.Builder loadBalanced, DownstreamClientsProperties properties) {
        return new RestUserDirectory(loadBalanced.clone().baseUrl(properties.userServiceUrl()).build());
    }

    @Bean
    EventCatalog eventCatalog(@LoadBalanced RestClient.Builder loadBalanced, DownstreamClientsProperties properties) {
        return new RestEventCatalog(loadBalanced.clone().baseUrl(properties.eventServiceUrl()).build());
    }
}
