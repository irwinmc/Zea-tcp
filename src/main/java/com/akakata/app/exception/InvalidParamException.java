package com.akakata.app.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * Reports invalid or missing request parameters with structured diagnostic details.
 *
 * @author Kyia
 */
public class InvalidParamException extends Exception {

    /**
     * Serialization version identifier.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Diagnostic-map key used to store an invalid-parameter problem description.
     */
    private static final String INVALID_PARAM_PROBLEM = "invalid_param_problem";

    /**
     * Named diagnostic or request parameters.
     *
     * @serial Named diagnostic or request parameters.
     */
    private final Map<String, Object> parameters = new HashMap<>();

    /**
     * Creates an invalid param exception instance.
     */
    public InvalidParamException() {

    }

    /**
     * Creates an invalid param exception instance with the supplied dependencies.
     *
     * @param problem the problem description
     */
    public InvalidParamException(String problem) {
        super(problem);
        if (problem != null) {
            parameters.put(INVALID_PARAM_PROBLEM, problem);
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
        return msg;
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
        return (String) getParameters().get(INVALID_PARAM_PROBLEM);
    }
}
