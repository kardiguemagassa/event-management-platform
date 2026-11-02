package com.kmg.eventplatform.gateway.bff;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Who is signed in, for the front-end: it never sees a token. Session logins only; a bearer-token caller already holds
 * its claims and gets {@code 401}.
 */
@RestController
class BffController {

    @GetMapping("/bff/me")
    ResponseEntity<BffUser> me(@AuthenticationPrincipal OidcUser user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(BffUser.from(user));
    }

    /** The signed-in user; the realm roles come from the ID token ({@code roles} claim of the BFF client). */
    record BffUser(String subject, String name, String email, List<String> roles) {

        static BffUser from(OidcUser user) {
            List<String> roles = user.getClaimAsStringList("roles");
            return new BffUser(user.getSubject(), user.getFullName(), user.getEmail(),
                    roles == null ? List.of() : List.copyOf(roles));
        }
    }
}
