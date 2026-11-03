package com.kmg.eventplatform.gateway.bff;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BffControllerTest {

    private final BffController controller = new BffController();

    @Test
    void signedInUserWithTheRealmRolesOfTheIdToken() {
        ResponseEntity<BffController.BffUser> response = controller.me(user(List.of("ATTENDEE", "ORGANIZER")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new BffController.BffUser(
                "user-id", "Jane Doe", "jane@example.com", List.of("ATTENDEE", "ORGANIZER")));
    }

    @Test
    void idTokenWithoutRolesGivesNoRole() {
        assertThat(controller.me(user(null)).getBody().roles()).isEmpty();
    }

    @Test
    void noSessionLoginIsUnauthorized() {
        assertThat(controller.me(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static DefaultOidcUser user(List<String> roles) {
        OidcIdToken.Builder idToken = OidcIdToken.withTokenValue("id-token")
                .subject("user-id").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("name", "Jane Doe").claim("email", "jane@example.com");
        if (roles != null) {
            idToken.claim("roles", roles);
        }
        return new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken.build());
    }
}
