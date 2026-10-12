package com.akakata.context.module;

import com.akakata.app.impl.DefaultGame;
import com.akakata.communication.OutboundLimits;
import com.akakata.context.ConfigurationManager;
import com.akakata.service.SessionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that application properties configure the Game, socket and registry providers.
 */
class FlowControlConfigurationRegressionTest {

    /**
     * Creates the configuration fixture.
     */
    FlowControlConfigurationRegressionTest() {
    }

    /**
     * Applies explicit limits rather than relying on hardcoded defaults.
     *
     * @throws Exception if the classpath configuration cannot be loaded
     */
    @Test
    void providersApplyConfiguredAdmissionBudgets() throws Exception {
        ConfigurationManager configuration = new ConfigurationManager("conf-flow-control.properties");
        ServiceModule module = new ServiceModule();
        SessionService sessions = module.provideSessionService(configuration, module.provideCredentialsVerifier(configuration));
        try (DefaultGame game = (DefaultGame) module.provideGame(configuration)) {
            assertEquals(2, sessions.maximumSessions());
            assertEquals(new com.akakata.server.HttpLimits(1024, 200, 300), ServiceModule.provideHttpLimits(configuration));
            assertEquals(512, ServiceModule.provideOutboundBudget(configuration).maximumBytes());
            try (var blocking = ServiceModule.provideBlockingTaskExecutor(configuration)) {
                assertTrue(blocking.submit(0, () -> true).get(1, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(16, game.getEventDispatcher().getQueueCapacity());
            assertEquals(256, game.getEventDispatcher().getQueueBytes());
            assertEquals(new com.akakata.server.TransportLimits(4, 100, 500, 1024),
                    ServiceModule.provideConnectionAdmission(configuration).limits());
            assertEquals(new OutboundLimits(3, 128, 64), ServiceModule.provideOutboundLimits(configuration));
        } finally {
            sessions.close();
        }
    }

    /**
     * Preserves byte budgets larger than the signed integer range.
     *
     * @throws Exception if the fixture resource cannot be read
     */
    @Test
    void byteConfigurationSupportsLongAllowances() throws Exception {
        ConfigurationManager configuration = new ConfigurationManager("conf-flow-control.properties");
        configuration.getProperties().setProperty("game.queue.bytes", "3221225472");
        configuration.getProperties().setProperty("socket.total.pending.bytes", "4294967296");
        try (var game = (DefaultGame) new ServiceModule().provideGame(configuration)) {
            assertEquals(3_221_225_472L, game.getEventDispatcher().getQueueBytes());
            assertEquals(4_294_967_296L, ServiceModule.provideOutboundBudget(configuration).maximumBytes());
        }
    }

}
