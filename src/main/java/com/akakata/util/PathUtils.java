package com.akakata.util;

/**
 * @author Kelvin
 */
public class PathUtils {

    public static String escape(String string) {
        if (null == string || string.isEmpty()) {
            return string;
        }

        String prefix = "";
        if (string.contains("://")) {
            prefix = string.substring(0, string.indexOf("://") + 3);
            string = string.replaceFirst(prefix, "");
        }
        string = string.replaceAll("\\\\", "/");

        // preserve // at the beginning for UNC paths
        if (string.startsWith("//")) {
            string = "/" + string.replaceAll("/+", "/");
        } else {
            string = string.replaceAll("/+", "/");
        }
        return prefix.concat(string);
    }
}
