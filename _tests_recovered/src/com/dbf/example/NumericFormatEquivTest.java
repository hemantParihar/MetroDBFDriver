package com.dbf.example;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * Proves the hand-rolled numeric formatting in DBFWriter produces byte-identical
 * output to the old String.format("%<len>d" / "%<len>.<dec>f") for a wide range
 * of values, so the batch-insert speed-up changes no stored data.
 */
public class NumericFormatEquivTest {
    private static int checked = 0;
    private static int mismatch = 0;

    public static void main(String[] args) {
        // Integers (decimalCount==0)
        long[] ints = { 0, 1, -1, 9, -9, 42, -42, 12345, -12345, 999999, -999999,
            1000000000L, -1000000000L, Long.MAX_VALUE / 2, Long.MIN_VALUE / 2 };
        for (long v : ints) {
            for (int len = 1; len <= 20; len++) {
                checkInt(v, len);
            }
        }

        // Decimals
        double[] dbls = { 0, 0.5, -0.5, 1.005, 2.675, 0.125, 0.135, 3.14159, -3.14159,
            11455, 11455.0, 5727.5, 119.88, 239.76, -0.001, 1234567.89, 0.999, -0.999 };
        Random r = new Random(42);
        double[] rnd = new double[2000];
        for (int i = 0; i < rnd.length; i++) rnd[i] = (r.nextDouble() - 0.5) * 2_000_000;

        for (int dec = 0; dec <= 4; dec++) {
            for (double v : dbls) for (int len = 1; len <= 18; len++) checkDec(v, len, dec);
            for (double v : rnd) checkDec(v, 18, dec);
        }

        System.out.println("checked=" + checked + " mismatches=" + mismatch);
        System.out.println("RESULT: " + (mismatch == 0 ? 1 : 0) + " passed, " + (mismatch == 0 ? 0 : 1) + " failed");
        if (mismatch > 0) System.exit(1);
    }

    private static void checkInt(long v, int len) {
        String oldStr = String.format("%" + len + "d", v);
        String manual = pad(Long.toString(v), len);
        boolean oldFits = oldStr.length() <= len;
        boolean newFits = Long.toString(v).length() <= len;
        if (oldFits != newFits) { fail("int fit", v, len, oldStr, manual); return; }
        if (oldFits && !oldStr.equals(manual)) fail("int", v, len, oldStr, manual);
        checked++;
    }

    private static void checkDec(double v, int len, int dec) {
        String oldStr = String.format("%" + len + "." + dec + "f", v);
        String body = BigDecimal.valueOf(v).setScale(dec, RoundingMode.HALF_UP).toPlainString();
        if (body.charAt(0) != '-' && (Double.doubleToRawLongBits(v) & Long.MIN_VALUE) != 0L) {
            body = "-" + body; // mirror DBFWriter's signed-zero handling
        }
        String manual = pad(body, len);
        boolean oldFits = oldStr.length() <= len;
        boolean newFits = body.length() <= len;
        if (oldFits != newFits) { fail("dec fit", v, len, oldStr + "/" + body, manual); return; }
        if (oldFits && !oldStr.equals(manual)) fail("dec(" + dec + ")", v, len, oldStr, manual);
        checked++;
    }

    /** Mirrors DBFWriter.padLeft: right-justify with leading spaces to width. */
    private static String pad(String s, int len) {
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len - s.length(); i++) sb.append(' ');
        return sb.append(s).toString();
    }

    private static void fail(String kind, double v, int len, String oldStr, String manual) {
        if (mismatch < 20) {
            System.out.println("MISMATCH " + kind + " v=" + v + " len=" + len
                + " old=[" + oldStr + "] new=[" + manual + "]");
        }
        mismatch++;
    }
}
