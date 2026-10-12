package com.akakata.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates process-local identifiers from one shared atomic counter.
 *
 * <p>{@link #generate()} optionally uses the JVM system property named by
 * {@link #NODE_NAME}. {@link #generateFor(Class)} always uses the supplied class
 * name and ignores that property. Both methods share the process-wide counter;
 * prefixes do not create independent counters.
 *
 * @author Kelvin
 */
public class IdGeneratorService {

    /**
     * JVM system-property key for the optional node prefix used by {@link #generate()}.
     *
     * <p>Set {@code -DZeaNode=game-1} to generate identifiers such as
     * {@code game-1-42}. This key is not loaded from the application properties file
     * and does not affect {@link #generateFor(Class)}.
     */
    public static final String NODE_NAME = "ZeaNode";

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
     * <p>The JVM system property named by {@link #NODE_NAME} is read on each call.
     * An absent or empty value produces a numeric identifier.
     *
     * @return a numeric identifier, or a node-prefixed string when the node-name property is set
     */
    public Object generate() {
        String nodeName = System.getProperty(NODE_NAME);
        if (null == nodeName || nodeName.isEmpty()) {
            return ID.incrementAndGet();
        } else {
            return nodeName + "-" + ID.incrementAndGet();
        }
    }

    /**
     * Increments the shared counter and prefixes the supplied class's simple name.
     * The JVM system property named by {@link #NODE_NAME} does not affect this method.
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
