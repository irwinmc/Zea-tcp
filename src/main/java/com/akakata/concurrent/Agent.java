package com.akakata.concurrent;

import org.jetlang.channels.Channel;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.core.Callback;
import org.jetlang.fibers.Fiber;

/**
 * @author Kelvin
 */
public class Agent {

    /**
     * The dedicated in-vm memory channel for this agent. Calls to this agent
     * get queued up on this channel for execution by the thread.
     */
    final Channel<Runnable> channel;

    /**
     * The fiber associated with this agent. Used to subscribe to the channel
     * and pass the incoming code to the callback for execution.
     */
    final Fiber fiber;

    /**
     * The incoming code is executed by this call back synchronously. Since the
     * send itself is asynchronous it acts like an event handler.
     */
    final Callback<Runnable> callback = (message) -> message.run();

    public Agent() {
        this.channel = new MemoryChannel<>();
        this.fiber = Fibers.pooledFiber();
        channel.subscribe(fiber, callback);
    }

    public void send(Runnable code) {
        channel.publish(code);
    }
}
