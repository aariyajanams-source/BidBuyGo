package com.bidbuygo.common.money;

/** Formatting for amounts held as long paise. */
public final class Money {

    private Money() {
    }

    /** 150050 -> "₹1500.50" */
    public static String format(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return "%s₹%d.%02d".formatted(sign, abs / 100, abs % 100);
    }
}
