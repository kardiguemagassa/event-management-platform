package com.kmg.eventplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesTest {

    private final KeycloakRealmRoles converter = new KeycloakRealmRoles();

    @Test
    void realmRolesBecomeSpringRoles() {
        assertThat(authorities(Map.of("roles", List.of("ATTENDEE", "ORGANIZER"))))
                .containsExactly("ROLE_ATTENDEE", "ROLE_ORGANIZER");
    }

    @Test
    void tokenWithoutRealmAccessGrantsNothing() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("user").issuedAt(Instant.now()).build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void realmAccessWithoutRoleListGrantsNothing() {
        assertThat(authorities(Map.of("roles", "ORGANIZER"))).isEmpty();
    }

    @Test
    void nonTextualRolesAreIgnored() {
        assertThat(authorities(Map.of("roles", List.of("ATTENDEE", 42)))).containsExactly("ROLE_ATTENDEE");
    }

    private List<String> authorities(Map<String, Object> realmAccess) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("realm_access", realmAccess).build();
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }
}
