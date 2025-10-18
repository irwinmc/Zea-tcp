package com.akakata.security;

import io.netty.buffer.ByteBuf;

public class SimpleCredentialsVerifier implements CredentialsVerifier {

    @Override
    public Credentials verify(ByteBuf byteBuf) {
        return new SimpleCredentials();
    }
}
