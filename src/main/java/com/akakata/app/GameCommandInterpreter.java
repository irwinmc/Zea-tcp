package com.akakata.app;

/**
 * Interprets application-specific command objects.
 *
 * @author Kelvin
 */
public interface GameCommandInterpreter {

    /**
     * Interprets and executes a command object.
     *
     * @param command the application command object
     * @throws Exception if command interpretation or execution fails
     */
    void interpretCommand(Object command) throws Exception;
}
