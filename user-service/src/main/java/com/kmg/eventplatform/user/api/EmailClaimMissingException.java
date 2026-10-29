package com.kmg.eventplatform.user.api;

/** The access token has no e-mail (Keycloak "email" client scope not granted): a profile cannot be saved. */
class EmailClaimMissingException extends RuntimeException {

    EmailClaimMissingException() {
        super("The access token carries no e-mail: grant the \"email\" scope to the client");
    }
}
