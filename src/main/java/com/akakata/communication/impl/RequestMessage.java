package com.akakata.communication.impl;

import com.akakata.app.exception.InvalidParamException;
import com.akakata.communication.MessageConstants;
import io.netty.channel.Channel;

import java.util.*;

/**
 * Carries a command identifier, named parameters and the originating Netty channel.
 *
 * <p>Fluent setters keep references to the supplied channel and parameter map.
 * Required-parameter validation checks key presence, including keys with null values.
 *
 * @author Kelvin
 */
public class RequestMessage {

    /**
     * Request message id.
     */
    private Integer id;

    /**
     * String key to value object mapping.
     */
    private Map<String, Object> parameters;

    /**
     * Associated transport channel.
     */
    private Channel channel;

    /**
     * Creates a request with an empty mutable parameter map.
     */
    public RequestMessage() {
        parameters = new HashMap<>();
    }

    /**
     * Returns the identifier.
     *
     * @return the identifier
     */
    public Integer getId() {
        return id;
    }

    /**
     * Sets the command identifier.
     *
     * @param id the identifier
     * @return this request for chaining
     */
    public RequestMessage id(Integer id) {
        this.id = id;
        return this;
    }

    /**
     * Returns the parameter.
     *
     * @param name the parameter or metric name
     * @return the parameter
     */
    public Object getParameter(String name) {
        return parameters.get(name);
    }

    /**
     * Checks that each required key exists, including keys whose value is null.
     *
     * @param names the required parameter names
     * @throws InvalidParamException if at least one requested parameter key is missing
     */
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

    /**
     * Replaces the parameter map without copying it.
     *
     * @param parameters the parameter map
     * @return this request for chaining
     */
    public RequestMessage parameters(Map<String, Object> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * Returns the associated Netty channel.
     *
     * @return the associated Netty channel
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * Sets the originating channel without transferring its ownership.
     *
     * @param channel the Netty transport channel
     * @return this request for chaining
     */
    public RequestMessage channel(Channel channel) {
        this.channel = channel;
        return this;
    }
}
