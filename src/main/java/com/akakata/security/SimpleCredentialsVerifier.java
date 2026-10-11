package com.akakata.security;

import io.netty.buffer.ByteBuf;

/**
 * Creates new random-key credentials without examining the login payload.
 *
 * <p>This default verifier is an extension point; it does not authenticate a user
 * against an account store.
 */
public class SimpleCredentialsVerifier implements CredentialsVerifier {

    /**
     * Creates a simple credentials verifier instance.
     */
    public SimpleCredentialsVerifier() {
    }

    /**
     * Creates fresh credentials without reading or validating the buffer.
     *
     * @param byteBuf the borrowed login payload buffer
     * @return new random-key credentials
     */
    @Override
    public Credentials verify(ByteBuf byteBuf) {
        return new SimpleCredentials();
    }
}
