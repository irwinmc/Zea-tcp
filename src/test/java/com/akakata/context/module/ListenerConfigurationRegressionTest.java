package com.akakata.context.module;

import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.server.NettyServer;
import com.akakata.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies explicit listener configuration before any socket is opened.
 */
class ListenerConfigurationRegressionTest {

    /**
     * Shared network owner for read-only provider checks; no listener is started.
     */
    private static NetworkBootstrap network;

    /**
     * Creates the listener configuration fixture.
     */
    ListenerConfigurationRegressionTest() {
    }

    /**
     * Allocates the shared network owner used by all provider checks.
     */
    @BeforeAll
    static void createNetwork() {
        network = new NetworkBootstrap(1, 1);
    }

    /**
     * Releases the shared network owner after the provider checks.
     */
    @AfterAll
    static void closeNetwork() {
        network.close();
    }

    /**
     * Defines the exact required port and address keys for each listener provider.
     */
    enum Listener {

        /**
         * Raw TCP listener configuration.
         */
        TCP("tcp.port", "tcp.bind.host"),

        /**
         * HTTP listener configuration.
         */
        HTTP("http.port", "http.bind.host"),

        /**
         * WebSocket listener configuration.
         */
        WEBSOCKET("web.socket.port", "web.socket.bind.host");

        /**
         * Required port key.
         */
        private final String portKey;

        /**
         * Required bind-address key.
         */
        private final String hostKey;

        /**
         * Captures the exact listener property names.
         *
         * @param portKey the required port key
         * @param hostKey the required bind-address key
         */
        Listener(String portKey, String hostKey) {
            this.portKey = portKey;
            this.hostKey = hostKey;
        }

        /**
         * Creates a configured server without opening a socket; initializers are unused.
         *
         * @param configuration the configured listener properties
         * @return the server whose bind settings are under test
         */
        private Server create(ConfigurationManager configuration) {
            return switch (this) {
                case TCP -> ServerModule.provideTcpServer(configuration, network, null);
                case HTTP -> ServerModule.provideHttpServer(configuration, network, null);
                case WEBSOCKET -> ServerModule.provideWebSocketServer(configuration, network, null);
            };
        }
    }

    /**
     * Applies the exact configured address and port, including an explicit ephemeral port.
     *
     * @param listener the provider under test
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @EnumSource(Listener.class)
    void providersUseExplicitAddressesAndPorts(Listener listener) throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        String host = listener == Listener.HTTP ? "0.0.0.0" : "127.0.0.2";
        configuration.getProperties().setProperty(listener.hostKey, host);
        for (int port : new int[]{0, 18090, 65535}) {
            configuration.getProperties().setProperty(listener.portKey, Integer.toString(port));
            var config = assertInstanceOf(NettyServer.class, listener.create(configuration)).getNettyConfig();
            assertEquals(host, config.getSocketAddress().getAddress().getHostAddress());
            assertEquals(port, config.getSocketAddress().getPort());
            assertEquals(port, config.getPortNumber());
        }
    }

    /**
     * Rejects missing keys without inferring an address or supplying a port default.
     *
     * @param listener the provider under test
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @EnumSource(Listener.class)
    void missingListenerPropertiesFailEvenWhenTheListenerIsDisabled(Listener listener) throws Exception {
        for (String key : new String[]{listener.portKey, listener.hostKey}) {
            var configuration = new ConfigurationManager("conf-lifecycle.properties");
            configuration.getProperties().remove(key);
            configuration.getProperties().setProperty("server." + listener.name().toLowerCase(java.util.Locale.ROOT) + ".enabled", "false");
            var failure = assertThrows(IllegalArgumentException.class, () -> listener.create(configuration));
            assertTrue(failure.getMessage().contains(key));
        }
    }

    /**
     * Rejects malformed, overflowing or out-of-range port values before socket startup.
     *
     * @param listener the provider under test
     * @param value the invalid port value
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @MethodSource("invalidPorts")
    void invalidListenerPortsFail(Listener listener, String value) throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        configuration.getProperties().setProperty(listener.portKey, value);
        var failure = assertThrows(IllegalArgumentException.class, () -> listener.create(configuration));
        assertTrue(failure.getMessage().contains(listener.portKey));
    }

    /**
     * Rejects blank and syntactically invalid addresses without selecting a fallback.
     *
     * @param listener the provider under test
     * @param value the invalid address value
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @MethodSource("invalidHosts")
    void invalidListenerHostsFail(Listener listener, String value) throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        configuration.getProperties().setProperty(listener.hostKey, value);
        var failure = assertThrows(IllegalArgumentException.class, () -> listener.create(configuration));
        assertTrue(failure.getMessage().contains(listener.hostKey));
    }

    /**
     * Combines every listener with malformed or out-of-range port values.
     *
     * @return invalid port scenarios
     */
    static Stream<Arguments> invalidPorts() {
        return Stream.of(Listener.values()).flatMap(listener -> List.of("", "   ", "invalid", "8090.5",
                "2147483648", "-1", "65536").stream().map(value -> Arguments.of(listener, value)));
    }

    /**
     * Combines every listener with empty or invalid address syntax.
     *
     * @return invalid address scenarios
     */
    static Stream<Arguments> invalidHosts() {
        return Stream.of(Listener.values()).flatMap(listener -> List.of("", "   ", "[broken]").stream()
                .map(value -> Arguments.of(listener, value)));
    }
}
