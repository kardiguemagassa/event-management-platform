package com.kmg.eventplatform.security.it;

import com.kmg.eventplatform.security.PlatformHttpSecurity;
import com.kmg.eventplatform.security.PlatformSecurityConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A minimal service secured like the real ones: the chassis plus its own access rules. */
@SpringBootApplication
@Import(PlatformSecurityConfiguration.class)
class SecuredTestApplication {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformHttpSecurity platform) throws Exception {
        return platform.resourceServer(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public").permitAll()
                        .requestMatchers("/seats").hasRole("SEAT_MANAGER")
                        .anyRequest().authenticated())
                .build();
    }

    @RestController
    static class Endpoints {

        @GetMapping("/public")
        String publicEndpoint() {
            return "public";
        }

        @GetMapping("/me")
        String me(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

        @GetMapping("/seats")
        String seats() {
            return "seats";
        }

        @GetMapping("/refused-by-the-controller")
        String refusedByTheController() {
            throw new AccessDeniedException("not the owner");
        }
    }

    /** Like the catch-all handler of every service: must not turn security failures into 500s. */
    @RestControllerAdvice
    static class CatchAllAdvice {

        @ExceptionHandler(Exception.class)
        ResponseEntity<String> unexpected(Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("unexpected");
        }
    }
}
