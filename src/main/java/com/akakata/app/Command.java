package com.akakata.app;

import com.akakata.app.exception.InvalidParamException;
import com.akakata.communication.impl.RequestMessage;

/**
 * Executes an application command for a player session and its request parameters.
 *
 * @author Kyia
 */
public interface Command {

    /**
     * Executes this command for the supplied player and request.
     *
     * @param session the session associated with the operation
     * @param request the inbound request
     * @throws InvalidParamException if required parameters are absent or invalid
     */
    void execute(PlayerSession session, RequestMessage request) throws InvalidParamException;
}
