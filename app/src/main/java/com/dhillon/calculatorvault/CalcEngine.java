package com.dhillon.calculatorvault;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Evaluates expressions such as "12+3×4−50%" with normal operator precedence. */
final class CalcEngine {

    static final char PLUS = '+';
    static final char MINUS = '−';
    static final char TIMES = '×';
    static final char DIVIDE = '÷';

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CalcEngine() {
    }

    static boolean isOperator(char c) {
        return c == PLUS || c == MINUS || c == TIMES || c == DIVIDE;
    }

    /** @throws ArithmeticException on division by zero, IllegalArgumentException on bad input. */
    static BigDecimal eval(String s) {
        List<BigDecimal> nums = new ArrayList<>();
        List<Character> ops = new ArrayList<>();
        int i = 0;
        int n = s.length();
        boolean expectNum = true;
        while (i < n) {
            char ch = s.charAt(i);
            if (expectNum) {
                boolean neg = false;
                if (ch == MINUS || ch == '-') {
                    neg = true;
                    i++;
                }
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                if (start == i) {
                    if (i >= n) break; // trailing operator / lone minus
                    throw new IllegalArgumentException("number expected");
                }
                String num = s.substring(start, i);
                if (num.equals(".")) throw new IllegalArgumentException("bad number");
                if (num.startsWith(".")) num = "0" + num;
                if (num.endsWith(".")) num = num + "0";
                BigDecimal v = new BigDecimal(num);
                while (i < n && s.charAt(i) == '%') {
                    v = v.divide(HUNDRED, MC);
                    i++;
                }
                nums.add(neg ? v.negate() : v);
                expectNum = false;
            } else {
                if (!isOperator(ch)) throw new IllegalArgumentException("operator expected");
                ops.add(ch);
                i++;
                expectNum = true;
            }
        }
        if (nums.isEmpty()) throw new IllegalArgumentException("empty");
        while (ops.size() >= nums.size()) ops.remove(ops.size() - 1);

        List<BigDecimal> sums = new ArrayList<>();
        List<Character> sumOps = new ArrayList<>();
        BigDecimal cur = nums.get(0);
        for (int k = 0; k < ops.size(); k++) {
            char op = ops.get(k);
            BigDecimal next = nums.get(k + 1);
            if (op == TIMES) {
                cur = cur.multiply(next, MC);
            } else if (op == DIVIDE) {
                if (next.signum() == 0) throw new ArithmeticException("Can't divide by 0");
                cur = cur.divide(next, MC);
            } else {
                sums.add(cur);
                sumOps.add(op);
                cur = next;
            }
        }
        sums.add(cur);
        BigDecimal r = sums.get(0);
        for (int k = 0; k < sumOps.size(); k++) {
            r = sumOps.get(k) == PLUS ? r.add(sums.get(k + 1), MC) : r.subtract(sums.get(k + 1), MC);
        }
        return r;
    }

    static String format(BigDecimal v) {
        if (v.signum() == 0) return "0";
        BigDecimal r = v.round(new MathContext(12)).stripTrailingZeros();
        BigDecimal abs = r.abs();
        String out;
        if (abs.compareTo(new BigDecimal("1e12")) >= 0 || abs.compareTo(new BigDecimal("1e-9")) < 0) {
            out = r.toString().replace("E+", "e").replace("E", "e");
        } else {
            out = r.toPlainString();
        }
        return out.replace('-', MINUS);
    }
}
