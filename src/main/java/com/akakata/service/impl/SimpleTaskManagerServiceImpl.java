package com.akakata.service.impl;

import com.akakata.app.Task;
import com.akakata.service.TaskManagerService;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Kelvin
 */
public class SimpleTaskManagerServiceImpl extends ScheduledThreadPoolExecutor implements TaskManagerService {

    public SimpleTaskManagerServiceImpl(int corePoolSize) {
        super(corePoolSize);
    }

    @Override
    public void execute(Task task) {
        super.execute(task);
    }

    @Override
    public ScheduledFuture<?> schedule(Task task, long delay, TimeUnit unit) {
        return super.schedule(task, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Task task, long initialDelay, long period, TimeUnit unit) {
        return super.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Task task, long initialDelay, long delay, TimeUnit unit) {
        return super.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
