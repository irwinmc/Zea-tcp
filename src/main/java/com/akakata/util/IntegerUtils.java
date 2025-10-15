package com.akakata.util;

import java.util.Arrays;

/**
 * Integer utilities
 *
 * @author Kyia
 */
public class IntegerUtils {

    /**
     * Generate min ~ max random number
     *
     * @param min
     * @param max
     * @return
     */
    public static int generateRandomSeed(int min, int max) {
        if (min == max) {
            return min;
        }

        double seed = Math.random() * (max - min) + min;
        return (int) Math.round(seed);
    }

    /**
     * Generate min ~ max no repeat numbers
     *
     * @param min
     * @param max
     * @param number
     * @return
     */
    public static int[] generateNorepeatRandomSeed(int min, int max, int number) {
        int[] rs = new int[number];
        Arrays.fill(rs, -1);
        for (int i = 0; i < number; i++) {
            int s = generateRandomSeed(min, max);
            boolean isHave = false;
            for (int r : rs) {
                if (r == s) {
                    isHave = true;
                    break;
                }
            }
            if (isHave) {
                i--;
                continue;
            }
            rs[i] = s;
        }
        return rs;
    }

    /**
     * 生成和为一定值的随机数组
     *
     * @param sum
     * @param size
     * @return
     */
    public static int[] generateRandomArray(int sum, int size) {
        int[] percent = new int[size];
        // 波动常数，位于0~1之间。如果=0，平均分配；=1，最大波动
        double k = 0.6;
        for (int i = 0; i < size - 1; i++) {
            double x = (double) sum / (size - i);
            double y = Math.random() - 0.5;
            double z = x + (y > 0 ? sum - x : x) * y * k;
            percent[i] = (int) z;
            sum -= percent[i];
        }
        percent[size - 1] = sum;
        return percent;
    }
}
