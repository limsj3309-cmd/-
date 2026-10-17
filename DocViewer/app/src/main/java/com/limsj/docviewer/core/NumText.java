package com.limsj.docviewer.core;

/** List-number formatting (decimal, roman, letters, Korean). */
public final class NumText {
    private NumText() {}

    private static final String GANADA = "가나다라마바사아자차카타파하";
    private static final String CHOSUNG = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ";

    public static String format(int n, String fmt) {
        if (fmt == null) fmt = "decimal";
        switch (fmt) {
            case "lowerRoman": return roman(n).toLowerCase(java.util.Locale.ROOT);
            case "upperRoman": return roman(n);
            case "lowerLetter": return letters(n, 'a');
            case "upperLetter": return letters(n, 'A');
            case "ganada": case "koreanDigital": return cycle(n, GANADA);
            case "chosung": return cycle(n, CHOSUNG);
            case "decimalEnclosedCircle": case "decimalEnclosedCircleChinese":
                return n >= 1 && n <= 20 ? String.valueOf((char) (0x2460 + n - 1)) : Integer.toString(n);
            case "decimalZero": return n < 10 ? "0" + n : Integer.toString(n);
            default: return Integer.toString(n);
        }
    }

    public static String cycle(int n, String set) {
        if (n < 1) n = 1;
        return String.valueOf(set.charAt((n - 1) % set.length()));
    }

    public static String letters(int n, char base) {
        if (n < 1) n = 1;
        char c = (char) (base + (n - 1) % 26);
        int rep = (n - 1) / 26 + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rep && i < 10; i++) sb.append(c);
        return sb.toString();
    }

    public static String roman(int n) {
        if (n <= 0 || n >= 4000) return Integer.toString(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) {
            n -= v[i];
            sb.append(s[i]);
        }
        return sb.toString();
    }
}
