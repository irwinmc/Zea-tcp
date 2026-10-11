package com.akakata.app;

/**
 * Stores player identity and display information independently of a session.
 *
 * @author Kelvin
 */
public interface Player {

    /**
     * Returns the identifier.
     *
     * @return the identifier
     */
    String getId();

    /**
     * Sets the identifier.
     *
     * @param id the identifier
     */
    void setId(String id);

    /**
     * Returns the player display name.
     *
     * @return the player display name
     */
    String getNickname();

    /**
     * Sets the player display name.
     *
     * @param nickname the player display name
     */
    void setNickname(String nickname);
}
