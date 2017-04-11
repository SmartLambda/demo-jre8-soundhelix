package com.soundhelix.util;

import java.util.Arrays;

/**
 * Implements the Bjorklund algorithm (due to E. Bjorklund), which is the basis for generating Euclidean rhythms, as mentioned in the paper
 * "The Euclidean Algorithm Generates Traditional Musical Rhythms" by Godfried Toussaint. The original paper can be found at
 * http://archive.bridgesmathart.org/2005/bridges2005-47.pdf.
 * 
 * @author Thomas Schuerger (thomas@schuerger.com)
 */

public final class EuclideanRhythmGenerator {

    /**
     * Private constructor.
     */

    private EuclideanRhythmGenerator() {
    }

    /**
     * Generates the Euclidean rhythm with the given number of pulses spread across the given number of steps.
     * 
     * @param pulses the number of pulses (must be >= 0 and must not be larger than steps)
     * @param steps the number of steps (must be > 0)
     * 
     * @return a step-element boolean array containing the Euclidean rhythm
     */

    public static boolean[] generate(int pulses, int steps) {
        if (pulses > steps || pulses < 0 || steps <= 0) {
            throw new IllegalArgumentException();
        }

        if (pulses == 0) {
            // easy case: all false
            return new boolean[steps];
        } else if (pulses == steps) {
            // easy case: all true
            boolean[] result = new boolean[steps];
            Arrays.fill(result, true);
            return result;
        }

        boolean[][] array = new boolean[steps][steps];
        int[] len = new int[steps];

        int pauses = steps - pulses;
        Arrays.fill(array[0], 0, pulses, true);
        len[0] = Math.max(pulses, pauses);
        len[1] = Math.min(pulses, pauses);

        int height = 2;
        int maxHeight = 1;
        int maxLen = len[0];
        int minLen = len[1];

        while (maxLen > minLen + 1) {
            int cutOff = Math.min(minLen, maxLen - minLen);
            for (int i = 0; i < maxHeight; i++) {
                System.arraycopy(array[i], len[i] - cutOff, array[height], 0, cutOff);
                len[i] -= cutOff;
                len[height] = cutOff;
                height++;
            }

            minLen = cutOff;
            maxLen = len[0];

            for (int i = height - 1; i >= maxHeight; i--) {
                if (len[i] == len[0]) {
                    maxHeight = i + 1;
                    break;
                }
            }
        }

        boolean[] result = new boolean[steps];

        int p = 0;
        for (int k = 0; k < len[0]; k++) {
            for (int i = 0; i < height; i++) {
                if (k < len[i]) {
                    result[p++] = array[i][k];
                }
            }
        }

        return result;
    }

    /**
     * Tests if the Euclidean rhythm generated by the given parameters equals the given expected value. If the test fails, an exception is thrown.
     * 
     * @param pulses the number of pulses
     * @param steps the number of steps
     * @param expected the expected pattern string (with true mapped to "x" and false mapped to ".")
     */

    private static void test(int pulses, int steps, String expected) {
        boolean[] result = generate(pulses, steps);

        if (result.length != expected.length()) {
            throw new RuntimeException("Pulses: " + pulses + "  Steps: " + steps + "  Wrong length: Expected " + expected.length() + ", got "
                    + result.length);
        }

        for (int i = 0; i < result.length; i++) {
            if (result[i] ^ expected.charAt(i) == 'x') {
                throw new RuntimeException("Pulses: " + pulses + "  Steps: " + steps + "  Wrong value at position " + i + ", expected: " + expected
                        + ", got: " + Arrays.toString(result));
            }
        }

    }

    public static void main(String[] args) {
        test(1, 2, "x.");
        test(1, 3, "x..");
        test(1, 4, "x...");
        test(4, 12, "x..x..x..x..");
        test(2, 3, "x.x");
        test(2, 5, "x.x..");
        test(3, 4, "x.xx");
        test(3, 5, "x.x.x");
        test(3, 7, "x.x.x..");
        test(3, 8, "x..x..x.");
        test(4, 7, "x.x.x.x");
        test(4, 9, "x.x.x.x..");
        test(4, 11, "x..x..x..x.");
        test(5, 6, "x.xxxx");
        test(5, 7, "x.xx.xx");
        test(5, 8, "x.xx.xx.");
        test(5, 9, "x.x.x.x.x");
        test(5, 11, "x.x.x.x.x..");
        test(5, 12, "x..x.x..x.x.");
        // E(5,16): corrected expected result from the paper (it was missing the last dot)
        test(5, 16, "x..x..x..x..x...");
        test(7, 8, "x.xxxxxx");
        test(7, 12, "x.xx.x.xx.x.");
        test(7, 16, "x..x.x.x..x.x.x.");
        test(9, 16, "x.xx.x.x.xx.x.x.");
        test(11, 24, "x..x.x.x.x.x..x.x.x.x.x.");
        test(13, 24, "x.xx.x.x.x.x.xx.x.x.x.x.");
    }
}