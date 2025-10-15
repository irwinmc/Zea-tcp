package com.akakata.event.impl;

import com.akakata.event.Event;

import java.io.Serializable;

/**
 * 事件包括三个元素：
 * <p/>
 * 事件类型
 * 事件值
 * 事件时间戳
 *
 * @author Kelvin
 */
public class DefaultEvent implements Event, Serializable {

    private static final long serialVersionUID = 4184094945897413320L;

    protected int type;
    protected Object source;
    protected long timeStamp;

    @Override
    public int getType() {
        return type;
    }

    @Override
    public void setType(int type) {
        this.type = type;
    }

    @Override
    public Object getSource() {
        return source;
    }

    @Override
    public void setSource(Object source) {
        this.source = source;
    }

    @Override
    public long getTimeStamp() {
        return timeStamp;
    }

    @Override
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    @Override
    public String toString() {
        return "Event [type=" + type + ", source=" + source + ", timeStamp=" + timeStamp + "]";
    }
}
