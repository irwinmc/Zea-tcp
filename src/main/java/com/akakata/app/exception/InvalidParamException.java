package com.akakata.app.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * 参数无效错误
 *
 * @author Kyia
 */
public class InvalidParamException extends Exception {

    private static final long serialVersionUID = 1L;

    private static final String INVALID_PARAM_PROBLEM = "invalid_param_problem";

    private final Map<String, Object> parameters = new HashMap<>();

    public InvalidParamException() {

    }

    public InvalidParamException(String problem) {
        super(problem);
        if (problem != null) {
            parameters.put(INVALID_PARAM_PROBLEM, problem);
        }
    }

    @Override
    public String getMessage() {
        String msg = super.getMessage();
        if (msg != null) {
            return msg;
        }
        msg = getProblem();
        return msg;
    }

    public void setParameter(String name, Object value) {
        getParameters().put(name, value);
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public String getProblem() {
        return (String) getParameters().get(INVALID_PARAM_PROBLEM);
    }
}
