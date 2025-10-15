package com.akakata.app.impl;

import com.akakata.app.Task;

public class WrapperTask implements Task {

    private Runnable task;

    public WrapperTask(Runnable task) {
        this.task = task;
    }

    @Override
    public void run() {
        try {
            task.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
