package com.akakata.service;

import com.akakata.security.Credentials;
import com.akakata.security.crypto.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

/**
 * 安全服务
 * <p>
 * 负责所有加密相关的操作，从 LoginService 中分离出来。
 * <p>
 * <b>职责：</b>
 * <ul>
 *   <li>生成加密 token</li>
 *   <li>验证 token</li>
 *   <li>密钥管理（未来扩展）</li>
 * </ul>
 *
 * @author Kelvin
 * @since 0.7.8
 */
@Singleton
public class SecurityService {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityService.class);

    /**
     * 生成加密 token
     * <p>
     * 使用 AES-GCM 加密凭证的 randomKey，返回加密后的 token。
     * 这个 token 会发送给客户端，用于后续请求的认证。
     *
     * @param credentials 凭证
     * @return 加密后的 token
     * @throws RuntimeException 如果加密失败
     */
    public String generateToken(Credentials credentials) {
        try {
            String randomKey = credentials.getRandomKey();
            String token = AesGcmCipher.encrypt(randomKey);
            LOG.debug("Token generated for credentials: {}", credentials);
            return token;
        } catch (Exception e) {
            LOG.error("Failed to generate token for credentials: {}", credentials, e);
            throw new RuntimeException("Token generation failed", e);
        }
    }

    /**
     * 验证 token
     * <p>
     * 解密 token，返回原始的 randomKey。
     *
     * @param encryptedToken 加密的 token
     * @return 解密后的 randomKey
     * @throws RuntimeException 如果解密失败
     */
    public String verifyToken(String encryptedToken) {
        try {
            String randomKey = AesGcmCipher.decrypt(encryptedToken);
            LOG.debug("Token verified successfully");
            return randomKey;
        } catch (Exception e) {
            LOG.error("Failed to verify token", e);
            throw new RuntimeException("Token verification failed", e);
        }
    }
}
