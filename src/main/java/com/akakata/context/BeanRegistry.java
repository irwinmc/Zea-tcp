package com.akakata.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple bean registry for storing and retrieving singleton beans.
 * Single responsibility: Bean storage and lookup.
 *
 * @author Kelvin
 */
public final class BeanRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BeanRegistry.class);

    private final ConcurrentHashMap<String, Object> beans = new ConcurrentHashMap<>();

    public void register(String name, Object bean) {
        if (name == null || bean == null) {
            throw new IllegalArgumentException("Bean name and instance cannot be null");
        }
        Object existing = beans.putIfAbsent(name, bean);
        if (existing != null) {
            LOG.warn("Bean {} already registered, replaced", name);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(String name, Class<T> type) {
        Object bean = beans.get(name);
        if (bean == null) {
            throw new IllegalArgumentException("Bean not found: " + name);
        }
        if (!type.isInstance(bean)) {
            throw new IllegalArgumentException("Bean " + name + " is not of type " + type.getName());
        }
        return (T) bean;
    }

    public Object getBean(String name) {
        Object bean = beans.get(name);
        if (bean == null) {
            LOG.warn("Bean not found: {}", name);
        }
        return bean;
    }

    public boolean contains(String name) {
        return beans.containsKey(name);
    }

    public void clear() {
        beans.clear();
    }

    public int size() {
        return beans.size();
    }
}
