package com.akakata.communication.impl;

import java.io.Serial;
import java.io.Serializable;

/**
 * Carries a request identifier, response code and optional serializable response data.
 *
 * @author Kelvin
 */
public class ReplyMessage implements Serializable {

    /**
     * Creates a reply message instance.
     */
    public ReplyMessage() {
    }

    /**
     * Serialization version identifier.
     */
    @Serial
    private static final long serialVersionUID = -1978059362334015359L;

    /**
     * Identifier used for request identity.
     *
     * @serial Identifier used for request identity.
     */
    private int id;
    /**
     * Application response code.
     *
     * @serial Application response code.
     */
    private int code;
    /**
     * Optional serializable reply payload.
     *
     * @serial Optional serializable reply payload.
     */
    private Serializable data;

    /**
     * Returns the identifier.
     *
     * @return the identifier
     */
    public int getId() {
        return id;
    }

    /**
     * Sets the identifier.
     *
     * @param id the identifier
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * Returns the response code.
     *
     * @return the response code
     */
    public int getCode() {
        return code;
    }

    /**
     * Sets the response code.
     *
     * @param code the response code
     */
    public void setCode(int code) {
        this.code = code;
    }

    /**
     * Returns the response payload.
     *
     * @return the response payload
     */
    public Serializable getData() {
        return data;
    }

    /**
     * Sets the response payload.
     *
     * @param data the response data, possibly null
     */
    public void setData(Serializable data) {
        this.data = data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "ReplyMessage [code=" + code + ", data=" + data + "]";
    }
}
