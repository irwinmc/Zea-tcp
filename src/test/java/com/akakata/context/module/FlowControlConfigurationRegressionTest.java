package com.akakata.context.module;

import com.akakata.app.impl.DefaultGame;
import com.akakata.communication.OutboundLimits;
import com.akakata.context.ConfigurationManager;
import com.akakata.service.SessionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        SessionService sessions = ServiceModule.provideSessionService(configuration);
        try (DefaultGame game = (DefaultGame) ServiceModule.provideGame(configuration)) {
            assertEquals(2, sessions.maximumSessions());
            assertEquals(16, game.getEventDispatcher().getQueueCapacity());
            assertEquals(256, game.getEventDispatcher().getQueueBytes());
            assertEquals(new com.akakata.server.TransportLimits(4, 100, 500, 1024),
                    ServiceModule.provideConnectionAdmission(configuration).limits());
            assertEquals(new OutboundLimits(3, 128), ServiceModule.provideOutboundLimits(configuration));
        } finally {
            sessions.close();
        }
    }
}
