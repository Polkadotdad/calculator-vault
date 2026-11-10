package com.dhillon.calculatorvault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.math.BigDecimal;

/** A normal, working calculator. Typing the secret code and pressing + opens the vault. */
public class MainActivity extends Activity {

    private static final int MAX_LEN = 60;

    private final StringBuilder expr = new StringBuilder();
    private boolean justEvaluated;
    private TextView exprView;
    private TextView previewView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        UiUtil.padForSystemBars(findViewById(R.id.root));
        exprView = findViewById(R.id.expression);
        previewView = findViewById(R.id.preview);
        wireKeys(findViewById(R.id.keypad));

        // Hidden: long-press "=" opens "forgot code" (only meaningful after set-up).
        findViewById(R.id.key_equals).setOnLongClickListener(v -> {
            Dialogs.recover(this, null);
            return true;
        });

        if (savedInstanceState != null) {
            expr.append(savedInstanceState.getString("expr", ""));
        }
        refresh();

        if (!Security.hasCode(this)) {
            Dialogs.setupCode(this, true, true, null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("expr", expr.toString());
    }

    private void wireKeys(View v) {
        if (v instanceof Button) {
            v.setOnClickListener(this::onKey);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) wireKeys(g.getChildAt(i));
        }
    }

    private void onKey(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        String tag = String.valueOf(v.getTag());
        switch (tag) {
            case "clear":
                expr.setLength(0);
                justEvaluated = false;
                break;
            case "back":
                if (justEvaluated) {
                    expr.setLength(0);
                    justEvaluated = false;
                } else if (expr.length() > 0) {
                    expr.setLength(expr.length() - 1);
                }
                break;
            case "=":
                equals();
                return;
            case ".":
                dot();
                break;
            case "%":
                percent();
                break;
            default:
                char c = tag.charAt(0);
                if (CalcEngine.isOperator(c)) {
                    if (c == CalcEngine.PLUS && tryOpenVault()) return;
                    operator(c);
                } else if (Character.isDigit(c)) {
                    digit(c);
                }
        }
        refresh();
    }

    private boolean tryOpenVault() {
        String s = expr.toString();
        if (Security.isValidCodeFormat(s) && Security.hasCode(this) && Security.checkCode(this, s)) {
            expr.setLength(0);
            justEvaluated = false;
            refresh();
            startActivity(new Intent(this, VaultActivity.class));
            return true;
        }
        return false;
    }

    private void digit(char d) {
        if (justEvaluated) {
            expr.setLength(0);
            justEvaluated = false;
        }
        if (expr.length() >= MAX_LEN) return;
        expr.append(d);
    }

    private String currentNumber() {
        int i = expr.length();
        while (i > 0 && (Character.isDigit(expr.charAt(i - 1)) || expr.charAt(i - 1) == '.')) i--;
        return expr.substring(i);
    }

    private void dot() {
        if (justEvaluated) {
            expr.setLength(0);
            justEvaluated = false;
        }
        String num = currentNumber();
        if (num.contains(".")) return;
        if (expr.length() > 0 && expr.charAt(expr.length() - 1) == '%') return;
        if (num.isEmpty()) expr.append('0');
        expr.append('.');
    }

    private void percent() {
        if (expr.length() == 0) return;
        char last = expr.charAt(expr.length() - 1);
        if (Character.isDigit(last) || last == '.') {
            expr.append('%');
            justEvaluated = false;
        }
    }

    private void operator(char op) {
        justEvaluated = false;
        if (expr.length() == 0) {
            if (op == CalcEngine.MINUS) expr.append(op);
            return;
        }
        char last = expr.charAt(expr.length() - 1);
        if (CalcEngine.isOperator(last)) {
            boolean allowUnaryMinus = op == CalcEngine.MINUS
                    && (last == CalcEngine.TIMES || last == CalcEngine.DIVIDE);
            if (allowUnaryMinus) {
                expr.append(op);
                return;
            }
            // Replace the trailing operator(s), e.g. "5×−" + "+" becomes "5+"
            while (expr.length() > 0 && CalcEngine.isOperator(expr.charAt(expr.length() - 1))) {
                expr.setLength(expr.length() - 1);
            }
            if (expr.length() == 0) return;
        }
        if (expr.length() >= MAX_LEN) return;
        expr.append(op);
    }

    private void equals() {
        if (expr.length() == 0) return;
        try {
            BigDecimal r = CalcEngine.eval(expr.toString());
            String out = CalcEngine.format(r);
            previewView.setText(expr.toString() + " =");
            expr.setLength(0);
            expr.append(out.contains("e") ? r.toPlainString().replace('-', CalcEngine.MINUS) : out);
            if (expr.length() > MAX_LEN) {
                expr.setLength(0);
                expr.append(out);
            }
            justEvaluated = true;
            exprView.setText(out);
        } catch (ArithmeticException e) {
            previewView.setText("Can't divide by 0");
        } catch (RuntimeException e) {
            previewView.setText("Error");
        }
    }

    private void refresh() {
        exprView.setText(expr.length() == 0 ? "0" : expr.toString());
        String s = expr.toString();
        boolean hasOp = false;
        for (int i = 1; i < s.length(); i++) {
            if (CalcEngine.isOperator(s.charAt(i)) || s.charAt(i) == '%') {
                hasOp = true;
                break;
            }
        }
        if (!hasOp || justEvaluated) {
            if (!justEvaluated) previewView.setText("");
            return;
        }
        try {
            previewView.setText(CalcEngine.format(CalcEngine.eval(s)));
        } catch (RuntimeException e) {
            previewView.setText("");
        }
    }
}
