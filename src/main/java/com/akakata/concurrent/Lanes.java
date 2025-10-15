package com.akakata.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * @author Kelvin
 */
public enum Lanes {

    // Singleton
    LANES;

    final String serverCores = System.getProperty("jet.lanes");
    final int numOfCores;
    final Lane<String, ExecutorService>[] jetLanes;

    @SuppressWarnings("unchecked")
    Lanes() {
        final Logger log = LoggerFactory.getLogger(Lanes.class);

        int cores = 1;
        if (serverCores != null) {
            try {
                cores = Integer.parseInt(serverCores);
            } catch (NumberFormatException e) {
                log.warn("Invalid server cores {}, going to ignore.", serverCores);
                // Ignore
            }
        }
        numOfCores = cores;
        jetLanes = new Lane[cores];
        ThreadFactory threadFactory = new NamedThreadFactory("Lane", true);
        for (int i = 1; i <= cores; i++) {
            DefaultLane defaultLane = new DefaultLane("Lane[" + i + "]", ManagedExecutor.newSingleThreadExecutor(threadFactory));
            jetLanes[i - 1] = defaultLane;
        }
    }

    public Lane<String, ExecutorService>[] getJetLanes() {
        return jetLanes;
    }

    public int getNumOfCores() {
        return numOfCores;
    }
}
