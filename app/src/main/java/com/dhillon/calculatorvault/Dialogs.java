package com.dhillon.calculatorvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** Set-up, change-code and recovery dialogs shared by the calculator and the vault. */
final class Dialogs {

    private Dialogs() {
    }

    static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        l.setPadding(p, dp(a, 8), p, 0);
        return l;
    }

    private static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(a, 10), 0, dp(a, 4));
        return t;
    }

    private static EditText codeField(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        return e;
    }

    /**
     * Asks for a new secret code (and, when {@code withRecovery}, a recovery question).
     * {@code firstTime} makes the dialog impossible to skip.
     */
    static void setupCode(Activity a, boolean firstTime, boolean withRecovery, Runnable done) {
        LinearLayout col = column(a);
        if (firstTime) {
            col.addView(label(a, "Choose a secret code of 4 to 8 digits (5 digits is a good choice).\n\n"
                    + "To open your hidden vault later: type the code on the calculator, then press  +"));
        }
        EditText code = codeField(a, "Secret code");
        EditText confirm = codeField(a, "Type the code again");
        col.addView(code);
        col.addView(confirm);

        Spinner question = new Spinner(a);
        EditText answer = new EditText(a);
        if (withRecovery) {
            col.addView(label(a, "Recovery question (used if you forget your code):"));
            ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, Security.QUESTIONS);
            question.setAdapter(ad);
            col.addView(question);
            answer.setHint("Your answer");
            answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            col.addView(answer);
        }

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(firstTime ? "Welcome to Calculator Vault" : "Set a new secret code")
                .setView(col)
                .setCancelable(!firstTime)
                .setPositiveButton("Save", null)
                .setNegativeButton(firstTime ? null : "Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String c1 = code.getText().toString();
            String c2 = confirm.getText().toString();
            if (!Security.isValidCodeFormat(c1)) {
                code.setError("Use 4 to 8 digits");
                return;
            }
            if (!c1.equals(c2)) {
                confirm.setError("Codes don't match");
                return;
            }
            if (withRecovery && answer.getText().toString().trim().length() < 2) {
                answer.setError("Please type an answer you will remember");
                return;
            }
            Security.setCode(a, c1);
            if (withRecovery) {
                Security.setRecovery(a, question.getSelectedItemPosition(), answer.getText().toString());
            }
            d.dismiss();
            Toast.makeText(a, "Saved. Type " + c1.length() + "-digit code, then +", Toast.LENGTH_LONG).show();
            if (done != null) done.run();
        }));
        d.show();
    }

    static void changeRecovery(Activity a) {
        LinearLayout col = column(a);
        Spinner question = new Spinner(a);
        question.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, Security.QUESTIONS));
        EditText answer = new EditText(a);
        answer.setHint("Your answer");
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(label(a, "Recovery question:"));
        col.addView(question);
        col.addView(answer);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Recovery question")
                .setView(col)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (answer.getText().toString().trim().length() < 2) {
                answer.setError("Please type an answer you will remember");
                return;
            }
            Security.setRecovery(a, question.getSelectedItemPosition(), answer.getText().toString());
            d.dismiss();
            Toast.makeText(a, "Recovery question saved", Toast.LENGTH_SHORT).show();
        }));
        d.show();
    }

    /** Forgot-code flow: correct recovery answer lets the user choose a new code. */
    static void recover(Activity a, Runnable onRecovered) {
        if (!Security.hasCode(a)) return;
        if (!Security.hasRecovery(a)) {
            new AlertDialog.Builder(a)
                    .setMessage("No recovery question was set, so the code can't be reset.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        long wait = Security.recoveryWaitMs(a);
        if (wait > 0) {
            Toast.makeText(a, "Too many tries. Wait " + (wait / 1000 + 1) + " seconds.", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout col = column(a);
        col.addView(label(a, Security.recoveryQuestion(a)));
        EditText answer = new EditText(a);
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(answer);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Forgot your code?")
                .setView(col)
                .setPositiveButton("Check", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((View v) -> {
            if (Security.recoveryWaitMs(a) > 0) {
                d.dismiss();
                Toast.makeText(a, "Too many tries. Please wait 30 seconds.", Toast.LENGTH_LONG).show();
                return;
            }
            if (Security.checkRecovery(a, answer.getText().toString())) {
                d.dismiss();
                setupCode(a, false, false, onRecovered);
            } else {
                answer.setError("That answer is not right");
            }
        }));
        d.show();
    }
}
