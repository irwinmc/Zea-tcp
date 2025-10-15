package com.akakata.concurrent;

/**
 * @param <ID_TYPE>
 * @param <UNDERLYING_LANE>
 * @author Kelvin
 */
public interface Lane<ID_TYPE, UNDERLYING_LANE> {

    boolean isOnSameLane(ID_TYPE currentLane);

    ID_TYPE getId();

    UNDERLYING_LANE getUnderlyingLane();
}
