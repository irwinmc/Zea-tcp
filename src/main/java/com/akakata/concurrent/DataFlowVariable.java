package com.akakata.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An anemic implementation of Gpars dataflow variable.
 *
 * @author Kelvin
 */
public class DataFlowVariable {

    final CountDownLatch latch;
    Object val = null;

    public DataFlowVariable() {
        this.latch = new CountDownLatch(1);
    }

    public DataFlowVariable(CountDownLatch latch) {
        this.latch = latch;
    }

    /**
     * The method will bind the incoming value to the value in the class and
     * then do a countDown on the latch
     *
     * @param val
     */
    public void bind(Object val) {
        this.val = val;
        latch.countDown();
    }

    /**
     * This method blocks till the count down latch has reset to 0.
     *
     * @return
     * @throws InterruptedException
     */
    public Object getVal() throws InterruptedException {
        latch.await();
        return val;
    }

    /**
     * This method blocks for a specific amount of time to retrieve the value
     * bound in bind method
     *
     * @param waitTime
     * @param timeUnit
     * @return
     * @throws InterruptedException
     */
    public Object getVal(long waitTime, TimeUnit timeUnit) throws InterruptedException {
        if (latch.await(waitTime, timeUnit)) {
            return val;
        } else {
            return null;
        }
    }
}
