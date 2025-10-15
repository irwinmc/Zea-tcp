package com.akakata.event.impl;

import com.akakata.app.Game;
import com.akakata.concurrent.Fibers;
import com.akakata.concurrent.Lane;
import com.akakata.concurrent.LaneStrategy;
import com.akakata.event.EventDispatcher;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.fibers.Fiber;

import java.util.concurrent.ExecutorService;

/**
 * @author Kelvin
 */
public class EventDispatchers {

    public static EventDispatcher newJetlangEventDispatcher(Game game, LaneStrategy<String, ExecutorService, Game> strategy) {
        Fiber fiber;
        JetlangEventDispatcher dispatcher;
        if (game == null) {
            fiber = Fibers.pooledFiber();
            dispatcher = new JetlangEventDispatcher(new MemoryChannel<>(), fiber, null);
        } else {
            Lane<String, ExecutorService> lane = strategy.chooseLane(game);
            fiber = Fibers.pooledFiber(lane);
            dispatcher = new JetlangEventDispatcher(new MemoryChannel<>(), fiber, lane);
        }
        dispatcher.initialize();

        return dispatcher;
    }
}
