package com.akakata.util;

import com.akakata.security.SimpleCredentials;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.security.Provider;
import java.security.Security;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks empty string framing and fail-closed credential identity generation.
 */
class StringAndRandomRegressionTest {

    /**
     * Creates the utility fixture.
     */
    StringAndRandomRegressionTest() {
    }

    /**
     * Consumes an empty frame both alone and between other strings.
     */
    @Test
    void emptyStringsRoundTripAndAdvanceTheReader() {
        ByteBuf empty = NettyUtils.writeString("");
        ByteBuf frames = NettyUtils.writeStrings("before", "", "after");
        try {
            assertEquals(4, empty.readableBytes());
            assertEquals("", NettyUtils.readString(empty));
            assertEquals(0, empty.readableBytes());
            assertEquals("before", NettyUtils.readString(frames));
            assertEquals("", NettyUtils.readString(frames));
            assertEquals("after", NettyUtils.readString(frames));
            assertEquals(0, frames.readableBytes());
        } finally {
            empty.release();
            frames.release();
        }
    }

    /**
     * Preserves the reader position of a four-byte header lacking a nonempty payload.
     */
    @Test
    void aLoneNonzeroLengthHeaderKeepsItsExistingReaderPosition() {
        for (int length : new int[]{1, -1}) {
            ByteBuf prefix = Unpooled.buffer(4).writeInt(length);
            try {
                int reader = prefix.readerIndex();
                assertNull(NettyUtils.readString(prefix));
                assertEquals(reader, prefix.readerIndex());
            } finally {
                prefix.release();
            }
        }
    }

    /**
     * Preserves nonpositive length defaults and explicitly requested lengths.
     */
    @Test
    void randomStringsKeepTheirLengthAndAlphabet() {
        for (int length : new int[]{-1, 0, 1, 8, 32}) {
            String value = RandomStringGenerator.generateRandomString(length);
            assertEquals(length > 0 ? length : 8, value.length());
            assertTrue(value.matches("[A-Za-z0-9]+"));
        }
    }

    /**
     * Removes the random provider only in an isolated child JVM.
     *
     * @throws Exception if process execution fails
     */
    @Test
    void missingRandomProviderCannotCreatePredictableCredentials() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("zea.test.classpath", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classpath, ProviderFailure.class.getName())
                .inheritIO().start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Exercises the real generator and credential constructor with no SHA1PRNG provider.
     */
    public static final class ProviderFailure {

        /**
         * Prevents construction of the child-process entry point.
         */
        private ProviderFailure() {
        }

        /**
         * Requires failures to retain the unavailable-provider cause.
         *
         * @param arguments unused process arguments
         */
        public static void main(String[] arguments) {
            int removed = 0;
            for (Provider provider : Security.getProviders()) {
                if (provider.getService("SecureRandom", "SHA1PRNG") != null) {
                    Security.removeProvider(provider.getName());
                    removed++;
                }
            }
            assertTrue(removed > 0);
            IllegalStateException generation = assertThrows(IllegalStateException.class,
                    () -> RandomStringGenerator.generateRandomString(8));
            assertInstanceOf(java.security.NoSuchAlgorithmException.class, generation.getCause());
            assertThrows(IllegalStateException.class, SimpleCredentials::new);
        }
    }
}
