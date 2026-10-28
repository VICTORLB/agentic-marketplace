package com.vx.marketplace.adapter.out.gcp;

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the OIDC token Pub/Sub attaches to push requests: signed by Google, issued for our audience, and minted
 * for the push service account we configured on the subscription.
 */
public class PushTokenVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(PushTokenVerifier.class);

    private final TokenVerifier verifier;
    private final String expectedServiceAccount;

    public PushTokenVerifier(String audience, String expectedServiceAccount) {
        this.verifier = TokenVerifier.newBuilder()
                .setAudience(audience)
                .setIssuer("https://accounts.google.com")
                .build();
        this.expectedServiceAccount = expectedServiceAccount;
    }

    public boolean isValid(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        try {
            JsonWebSignature jws = verifier.verify(authorizationHeader.substring("Bearer ".length()));
            Object email = jws.getPayload().get("email");
            return expectedServiceAccount.equals(email);
        } catch (TokenVerifier.VerificationException e) {
            LOG.warn("Rejected push token: {}", e.getMessage());
            return false;
        }
    }
}
