package com.akakata.security;

import io.netty.buffer.ByteBuf;

/**
 * 凭证验证器接口
 * <p>
 * 用于依赖注入，避免在 SessionService 中硬编码验证逻辑。
 */
public interface CredentialsVerifier {

    Credentials verify(ByteBuf byteBuf);
}
