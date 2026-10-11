package com.akakata.security;

import io.netty.buffer.ByteBuf;

/**
 * Extracts and verifies credentials from a login payload without taking buffer ownership.
 */
public interface CredentialsVerifier {

    /**
     * Extracts and verifies credentials from the supplied login payload.
     *
     * <p>The buffer is borrowed; verification does not transfer ownership to the verifier.
     *
     * @param byteBuf the borrowed login payload buffer
     * @return the credentials, or {@code null} if verification fails
     */
    Credentials verify(ByteBuf byteBuf);
}
