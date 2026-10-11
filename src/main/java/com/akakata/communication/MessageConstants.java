package com.akakata.communication;

/**
 * Defines keys used to report missing request parameters.
 *
 * @author Kelvin
 */
public interface MessageConstants {

    /**
     * Problem code for a missing required parameter.
     */
    String PARAMETER_ABSENT = "parameter_absent";

    /**
     * Diagnostic key containing the list of missing parameter names.
     */
    String REQUEST_PARAMETERS_ABSENT = "request_parameters_absent";
}
