package com.akakata.service.impl;

import com.akakata.service.UniqueIdGeneratorService;
import com.akakata.util.ZealConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Kelvin
 */
public class SimpleUniqueIdGeneratorImpl implements UniqueIdGeneratorService {

    public static final AtomicLong ID = new AtomicLong(0L);

    @Override
    public Object generate() {
        String nodeName = System.getProperty(ZealConfig.NODE_NAME);
        if (null == nodeName || nodeName.isEmpty()) {
            return ID.incrementAndGet();
        } else {
            return nodeName + ID.incrementAndGet();
        }
    }

    @Override
    public Object generateFor(Class<?> klass) {
        return klass.getSimpleName() + ID.incrementAndGet();
    }
}
