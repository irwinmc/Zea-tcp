package com.akakata.concurrent;

import org.jetlang.channels.Channel;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.core.Callback;
import org.jetlang.fibers.Fiber;

/**
 * @author Kelvin
 */
public class JetlangActor<T> {

    private final Channel<T> inChannel;
    private final Channel<T> outChannel;
    private final Callback<T> callback;
    private Fiber fiber;

    public JetlangActor(Channel<T> inChannel, Channel<T> outChannel, Fiber fiber, Callback<T> callback) {
        this.inChannel = inChannel;
        this.outChannel = outChannel;
        this.fiber = fiber;
        this.callback = callback;
    }

    public JetlangActor() {
        this.inChannel = new MemoryChannel<T>();
        this.outChannel = new MemoryChannel<T>();
        this.callback = new Callback<T>() {
            @Override
            public void onMessage(T message) {
                act(message);
            }
        };
        this.fiber = Fibers.pooledFiber();
    }

    public void start() {
        // subscribe to incoming channel
        inChannel.subscribe(fiber, callback);
    }

    public void act(T message) {

    }

    public void sendMessage(T message) {
        outChannel.publish(message);
    }

    public void addEventListener() {

    }
}
