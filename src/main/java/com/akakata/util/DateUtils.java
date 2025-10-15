package com.akakata.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Date utilities
 *
 * @author Kyia
 */
public class DateUtils {

    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";

    private static final String DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * Constructor
     */
    public DateUtils() {

    }

    /**
     * Format time with default time pattern
     *
     * @param time
     * @return
     */
    public static String formatTime(long time) {
        return new SimpleDateFormat(DEFAULT_TIME_FORMAT).format(new Date(time));
    }

    /**
     * Format time with default date pattern
     *
     * @param time
     * @return
     */
    public static String formatDate(long time) {
        return new SimpleDateFormat(DEFAULT_DATE_FORMAT).format(new Date(time));
    }

    /**
     * Format time with pattern
     *
     * @param time
     * @param pattern
     * @return
     */
    public static String formatTime(long time, String pattern) {
        return new SimpleDateFormat(pattern).format(new Date(time));
    }

    /**
     * Parse time
     *
     * @param str
     * @return
     */
    public static long parseTime(String str) {
        long time = System.currentTimeMillis();
        try {
            time = new SimpleDateFormat(DEFAULT_DATE_FORMAT).parse(str).getTime();
        } catch (ParseException e) {
            // e...
        }
        return time;
    }

    /**
     * Parse time
     *
     * @param str
     * @param pattern
     * @return
     */
    public static long parseTime(String str, String pattern) {
        long time = System.currentTimeMillis();
        try {
            time = new SimpleDateFormat(pattern).parse(str).getTime();
        } catch (ParseException e) {
            // e...
        }
        return time;
    }

    /**
     * Get zero time, a day's begin
     *
     * @param time
     * @return
     */
    public static long getZeroTime(long time) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DEFAULT_DATE_FORMAT);
        String str = dateFormat.format(new Date(time));
        long zeroTime = time;
        try {
            Date date = dateFormat.parse(str);
            zeroTime = date.getTime();
        } catch (ParseException e) {
            // e....
        }
        return zeroTime;
    }

    /**
     * Get day interval
     * end must bigger than start
     * end > start
     *
     * @param start
     * @param end
     * @return
     */
    public static int getDayInterval(long start, long end) {
        long startZeroTime = getZeroTime(start);
        long endZeroTime = getZeroTime(end);

        return (int) ((endZeroTime - startZeroTime) / (24 * 60 * 60 * 1000));
    }
}

