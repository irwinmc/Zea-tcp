package com.akakata.app.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * Reports an invalid command with a problem description and diagnostic parameters.
 *
 * @author Kyia
 */
public class InvalidCommandException extends Exception {

    /**
     * Serialization version identifier.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Diagnostic-map key used to store an invalid-command problem description.
     */
    private static final String INVALID_COMMAND_PROBLEM = "invalid_param_problem";

    /**
     * Named diagnostic or request parameters.
     *
     * @serial Named diagnostic or request parameters.
     */
    private final Map<String, Object> parameters = new HashMap<String, Object>();

    /**
     * Creates an invalid command exception instance.
     */
    public InvalidCommandException() {

    }

    /**
     * Creates an invalid command exception instance with the supplied dependencies.
     *
     * @param problem the problem description
     */
    public InvalidCommandException(String problem) {
        super(problem);
        if (problem != null) {
            parameters.put(INVALID_COMMAND_PROBLEM, problem);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMessage() {
        String msg = super.getMessage();
        if (msg != null) {
            return msg;
        }
        msg = getProblem();
        if (msg != null) {
            return msg;
        }
        return null;
    }

    /**
     * Sets the parameter.
     *
     * @param name the parameter or metric name
     * @param value the value to store or encode
     */
    public void setParameter(String name, Object value) {
        getParameters().put(name, value);
    }

    /**
     * Returns the diagnostic parameter map.
     *
     * @return the diagnostic parameter map
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * Returns the problem description.
     *
     * @return the problem description
     */
    public String getProblem() {
        return (String) getParameters().get(INVALID_COMMAND_PROBLEM);
    }
}
