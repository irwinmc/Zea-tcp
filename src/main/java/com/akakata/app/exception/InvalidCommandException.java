package com.akakata.app.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * 命令无效错误
 *
 * @author Kyia
 */
public class InvalidCommandException extends Exception {

    private static final long serialVersionUID = 1L;

    private static final String INVALID_COMMAND_PROBLEM = "invalid_param_problem";
    private final Map<String, Object> parameters = new HashMap<String, Object>();

    public InvalidCommandException() {

    }

    public InvalidCommandException(String problem) {
        super(problem);
        if (problem != null) {
            parameters.put(INVALID_COMMAND_PROBLEM, problem);
        }
    }

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

    public void setParameter(String name, Object value) {
        getParameters().put(name, value);
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public String getProblem() {
        return (String) getParameters().get(INVALID_COMMAND_PROBLEM);
    }
}
