package com.akakata.concurrent;

import com.akakata.app.Game;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A session choosing a Lane can be done based on strategy. The enumeration
 * already has 2 default implementations. Users can implement their own
 * sophisticated ones based on usecase.
 *
 * @param <LANE_ID_TYPE>
 * @param <UNDERLYING_LANE>
 * @param <GROUP>
 * @author Kelvin
 */
public interface LaneStrategy<LANE_ID_TYPE, UNDERLYING_LANE, GROUP> {

    /**
     * The method of choosing strategy
     *
     * @param group
     * @return
     */
    Lane<LANE_ID_TYPE, UNDERLYING_LANE> chooseLane(GROUP group);

    enum LaneStrategies implements LaneStrategy<String, ExecutorService, Game> {
        /**
         * 轮叫调度，多条通道轮询使用。
         */
        ROUND_ROBIN {
            final AtomicInteger CURRENT_LANE = new AtomicInteger(0);
            final int LANE_SIZE = lanes.length;

            @Override
            public Lane<String, ExecutorService> chooseLane(Game game) {
                CURRENT_LANE.compareAndSet(LANE_SIZE, 0);
                return lanes[CURRENT_LANE.getAndIncrement()];
            }
        },
        /**
         * 根据游戏分组，每次有会话加入，会根据其所在的游戏查询通道分组，进而使用该通道。
         * 对于单通道中有过多的会话，不建议使用。
         */
        GROUP_BY_GAME {
            private final ConcurrentMap<Game, Lane<String, ExecutorService>> GAME_LANE_MAP
                    = new ConcurrentHashMap<>();
            private final ConcurrentMap<Lane<String, ExecutorService>, AtomicInteger> LANE_SESSION_COUNTER
                    = new ConcurrentHashMap<>();

            @Override
            public Lane<String, ExecutorService> chooseLane(Game game) {
                Lane<String, ExecutorService> lane = GAME_LANE_MAP.get(game);
                if (lane == null) {
                    synchronized (LANE_SESSION_COUNTER) {
                        if (LANE_SESSION_COUNTER.isEmpty()) {
                            for (Lane<String, ExecutorService> theLane : lanes) {
                                LANE_SESSION_COUNTER.put(theLane, new AtomicInteger(0));
                            }
                        }
                        Set<Lane<String, ExecutorService>> laneSet = LANE_SESSION_COUNTER.keySet();
                        int min = 0;
                        for (Lane<String, ExecutorService> theLane : laneSet) {
                            AtomicInteger counter = LANE_SESSION_COUNTER.get(theLane);
                            int numOfSessions = counter.get();
                            if (numOfSessions == 0) {
                                lane = theLane;
                                break;
                            } else {
                                if (min == 0) {
                                    min = numOfSessions;
                                    lane = theLane;
                                }
                                if (numOfSessions < min) {
                                    min = numOfSessions;
                                    lane = theLane;
                                }
                            }
                        }
                        GAME_LANE_MAP.put(game, lane);
                    }
                }
                // A new session has chosen the lane, hence the session counter
                // needs to be incremented.
                LANE_SESSION_COUNTER.get(lane).incrementAndGet();
                // TODO: how to reduce count on session close?
                return lane;
            }

        };

        final Lane<String, ExecutorService>[] lanes = Lanes.LANES.getJetLanes();
    }
}
