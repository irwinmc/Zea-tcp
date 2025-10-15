package com.akakata.service;

import com.akakata.app.Task;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Kelvin
 */
public interface TaskManagerService {

    void execute(Task task);

    ScheduledFuture<?> schedule(Task task, long delay, TimeUnit unit);

    ScheduledFuture<?> scheduleAtFixedRate(Task task, long initialDelay, long period, TimeUnit unit);

    ScheduledFuture<?> scheduleWithFixedDelay(Task task, long initialDelay, long delay, TimeUnit unit);

    void shutdown();
}
