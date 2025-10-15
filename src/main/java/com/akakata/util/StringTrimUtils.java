package com.akakata.util;

/**
 * 字符串统一处理工具
 *
 * @author Kyia
 */
public class StringTrimUtils {

    /**
     * Trim method
     *
     * @param str                string to trim
     * @param specialCharsLength special character's length, chinese, japanese, korea's char length is 2
     * @return
     */
    public static String trim(String str, int specialCharsLength) {
        if (str == null || str.isEmpty() || specialCharsLength < 1) {
            return "";
        }
        char[] chars = str.toCharArray();
        int charsLength = getCharsLength(chars, specialCharsLength);
        return new String(chars, 0, charsLength);
    }

    /**
     * Get chars length with the special chars' length
     *
     * @param chars              array of chars
     * @param specialCharsLength special character's length, chinese, japanese, korea's char length is 2
     * @return the length of chars
     */
    public static int getCharsLength(char[] chars, int specialCharsLength) {
        int count = 0;
        int normalCharsLength = 0;
        for (char aChar : chars) {
            int specialCharLength = getSpecialCharLength(aChar);
            if (count <= specialCharsLength - specialCharLength) {
                count += specialCharLength;
                normalCharsLength++;
            } else {
                break;
            }
        }
        return normalCharsLength;
    }

    /**
     * Get some characters' length
     *
     * @param chars a collections of characters
     * @return the length
     */
    public static int getCharsLength(char[] chars) {
        int count = 0;
        for (char aChar : chars) {
            int specialCharLength = getSpecialCharLength(aChar);
            count += specialCharLength;
        }
        return count;
    }

    /**
     * Get special character's length
     *
     * @param c character to check
     * @return the length of the character
     */
    private static int getSpecialCharLength(char c) {
        if (isLetter(c)) {
            return 1;
        } else {
            return 2;
        }
    }

    /**
     * Check a character is a letter
     *
     * @param c character need to be checked
     * @return true if is a ASCII letter
     */
    private static boolean isLetter(char c) {
        int k = 0x80;
        return c / k == 0;
    }
}
