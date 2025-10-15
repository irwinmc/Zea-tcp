package com.akakata.communication.impl;

import java.io.Serializable;

/**
 * @author Kelvin
 */
public class ReplyMessage implements Serializable {

    private static final long serialVersionUID = -1978059362334015359L;

    private int id;
    private int code;
    private Serializable data;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public Serializable getData() {
        return data;
    }

    public void setData(Serializable data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "ReplyMessage [code=" + code + ", data=" + data + "]";
    }
}
