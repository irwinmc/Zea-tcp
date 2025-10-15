package com.akakata.util;

import java.util.*;

/**
 * @author Kelvin
 */
public class LikeHashMap<K, V> extends HashMap<K, V> {

    private static final long serialVersionUID = -2618183812674267248L;

    public List<V> get(String key, boolean like) {
        List<V> list = null;
        if (like) {
            list = new ArrayList<>();
            Set<K> set = this.keySet();
            for (K elem : set) {
                if (elem.toString().contains(key)) {
                    list.add(this.get(elem));
                }
            }
        }
        return list;
    }
}