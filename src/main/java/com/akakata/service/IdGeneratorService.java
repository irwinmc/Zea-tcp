package com.akakata.service;

import com.akakata.util.ZealConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates process-local identifiers from one shared atomic counter.
 *
 * <p>Identifiers can include a configured node name or a class-name prefix; prefixes
 * do not create independent counters.
 *
 * @author Kelvin
 */
public class IdGeneratorService {

    /**
     * Creates an id generator service instance.
     */
    public IdGeneratorService() {
    }

    /**
     * Process-wide atomic identifier sequence.
     */
    private static final AtomicLong ID = new AtomicLong(0L);

    /**
     * Increments the shared counter and optionally prefixes the configured node name.
     *
     * @return a numeric identifier, or a node-prefixed string when the node-name property is set
     */
    public Object generate() {
        String nodeName = System.getProperty(ZealConfig.NODE_NAME);
        if (null == nodeName || nodeName.isEmpty()) {
            return ID.incrementAndGet();
        } else {
            return nodeName + "-" + ID.incrementAndGet();
        }
    }

    /**
     * Increments the shared counter and prefixes the supplied class's simple name.
     *
     * @param klass the class whose simple name prefixes the identifier
     * @return the class-prefixed identifier
     */
    public Object generateFor(Class<?> klass) {
        return klass.getSimpleName() + "-" + ID.incrementAndGet();
    }

    /**
     * Resets the process-wide counter to zero for isolated tests.
     *
     * <p>Do not call while live resources rely on identifier uniqueness.
     */
    public void resetForTesting() {
        ID.set(0L);
    }
}
