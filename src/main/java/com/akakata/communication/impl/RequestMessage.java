package com.akakata.communication.impl;

import com.akakata.app.exception.InvalidParamException;
import com.akakata.communication.MessageConstants;
import io.netty.channel.Channel;

import java.util.*;

/**
 * @author Kelvin
 */
public class RequestMessage {

    /**
     * Request message id
     */
    private Integer id;

    /**
     * String key to value object mapping
     */
    private Map<String, Object> parameters;

    private Channel channel;

    public RequestMessage() {
        parameters = new HashMap<>();
    }

    public Integer getId() {
        return id;
    }

    public RequestMessage id(Integer id) {
        this.id = id;
        return this;
    }

    public Object getParameter(String name) {
        return parameters.get(name);
    }

    public void requireParameters(String... names) throws InvalidParamException {
        Set<String> present = parameters.keySet();
        List<String> absent = new ArrayList<>();
        for (String required : names) {
            if (!present.contains(required)) {
                absent.add(required);
            }
        }
        if (!absent.isEmpty()) {
            InvalidParamException problem = new InvalidParamException(MessageConstants.PARAMETER_ABSENT);
            problem.setParameter(MessageConstants.REQUEST_PARAMETERS_ABSENT, absent.toString());
            throw problem;
        }
    }

    public RequestMessage parameters(Map<String, Object> parameters) {
        this.parameters = parameters;
        return this;
    }

    public Channel getChannel() {
        return channel;
    }

    public RequestMessage channel(Channel channel) {
        this.channel = channel;
        return this;
    }
}
