package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Forgot password, as sketched:
 *   1. email, then Proceed
 *   2. the token (code) from the email. Valid for 10 minutes, 5 tries, Resend after 30 seconds
 *   3. only after the token is correct: new password and confirm password, then Proceed
 *   4. "Password updated", back to sign in
 *
 * Page 1 always moves on, whether or not the email is registered, so the screen never reveals
 * which emails have accounts. All server work goes through Auth.get() (see AuthService).
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String S_STEP = "step";
    private static final String S_EMAIL = "email";
    private static final String S_RESEND_END = "resend_end";

    private TextInputLayout layoutEmail, layoutCode, layoutPassword, layoutConfirm;
    private TextInputEditText inputEmail, inputCode, inputPassword, inputConfirm;
    private Button buttonProceed, buttonVerify, buttonUpdate, buttonBack2, buttonBack3;
    private TextView textResend, textStepLabel, textCodeSub, textDemoNote, textFormError;
    private View progress, stepHeader;
    private final View[] dots = new View[3];

    private final ResendTimer resendTimer = new ResendTimer();
    private int step = 1;
    private boolean busy;
    private boolean showDemo;
    private String email = "";
    private String verifiedCode = ""; // kept only in memory, only after the server accepted it
    private long resendEndsAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.forgot_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        bindViews();
        showDemo = getResources().getBoolean(R.bool.show_demo_hints);

        clearOnType(inputEmail, layoutEmail);
        clearOnType(inputCode, layoutCode);
        clearOnType(inputPassword, layoutPassword);
        clearOnType(inputConfirm, layoutConfirm);
        onDone(inputEmail, this::proceed);
        onDone(inputCode, this::verify);
        onDone(inputConfirm, this::updatePassword);

        buttonProceed.setOnClickListener(v -> proceed());
        buttonVerify.setOnClickListener(v -> verify());
        buttonUpdate.setOnClickListener(v -> updatePassword());
        buttonBack2.setOnClickListener(v -> goBack());
        buttonBack3.setOnClickListener(v -> goBack());
        textResend.setOnClickListener(v -> {
            if (!busy && SystemClock.elapsedRealtime() >= resendEndsAt) sendCode(true);
        });
        findViewById(R.id.buttonBackToSignIn).setOnClickListener(v -> toSignIn());

        if (savedInstanceState != null) {
            email = savedInstanceState.getString(S_EMAIL, "");
            resendEndsAt = savedInstanceState.getLong(S_RESEND_END, 0);
            int saved = savedInstanceState.getInt(S_STEP, 1);
            // the verified code is not kept across a rotation, so page 3 goes back to page 2
            showStep(saved == 3 ? 2 : saved);
            if (step == 2) startResendTimer((int) Math.max(0, (resendEndsAt - SystemClock.elapsedRealtime() + 999) / 1000));
        } else {
            // Sign in passes what was typed in its first field; use it if it is an email
            String prefill = getIntent().getStringExtra(LoginActivity.EXTRA_PREFILL_NUMBER);
            if (prefill != null && prefill.contains("@")) inputEmail.setText(prefill.trim());
            showStep(1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(S_STEP, step);
        out.putString(S_EMAIL, email);
        out.putLong(S_RESEND_END, resendEndsAt);
    }

    @Override
    protected void onDestroy() {
        resendTimer.cancel();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- page 1: email

    private void proceed() {
        if (busy) return;
        hideFormError();
        String typed = text(inputEmail).trim();
        ContactValidator.EmailResult r = ContactValidator.checkEmail(typed);
        if (!ContactValidator.isOk(r)) {
            layoutEmail.setError(getString(r == ContactValidator.EmailResult.EMPTY
                    ? R.string.error_email_empty : R.string.error_email_invalid));
            inputEmail.requestFocus();
            return;
        }
        email = typed;
        sendCode(false);
    }

    private void sendCode(boolean resend) {
        setBusy(true);
        hideFormError();
        Auth.get().requestResetCode(email, result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            if (result.ok()) {
                Toast.makeText(this, resend ? R.string.toast_code_resent : R.string.toast_code_sent, Toast.LENGTH_SHORT).show();
                inputCode.setText("");
                showStep(2);
                startResendTimer(AuthService.RESEND_SECONDS);
            } else {
                showFormError(R.string.error_network);
            }
        });
    }

    // ---------------------------------------------------------------- page 2: token

    private void verify() {
        if (busy) return;
        hideFormError();
        String code = ContactValidator.normaliseCode(text(inputCode));
        ContactValidator.CodeResult check = ContactValidator.checkCode(code);
        if (!ContactValidator.isOk(check)) {
            layoutCode.setError(getString(check == ContactValidator.CodeResult.EMPTY
                    ? R.string.error_code_empty : R.string.error_code_invalid));
            return;
        }
        setBusy(true);
        Auth.get().verifyResetCode(email, code, result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
                    verifiedCode = code;
                    resendTimer.cancel();
                    showStep(3);
                    break;
                case BAD_CODE:
                    layoutCode.setError(getString(R.string.error_code_wrong_fmt, result.triesLeft));
                    break;
                case CODE_EXPIRED:
                    layoutCode.setError(getString(R.string.error_code_expired));
                    enableResendNow();
                    break;
                case TOO_MANY_TRIES:
                    layoutCode.setError(getString(R.string.error_code_locked));
                    enableResendNow();
                    break;
                default:
                    showFormError(R.string.error_network);
            }
        });
    }

    private void startResendTimer(int seconds) {
        resendEndsAt = SystemClock.elapsedRealtime() + seconds * 1000L;
        textResend.setEnabled(false);
        textResend.setTextColor(ContextCompat.getColor(this, R.color.slate_400));
        resendTimer.start(seconds, new ResendTimer.Listener() {
            @Override
            public void onTick(int secondsLeft) {
                textResend.setText(getString(R.string.resend_in_fmt, ResendTimer.clock(secondsLeft)));
            }

            @Override
            public void onFinish() {
                enableResendNow();
            }
        });
    }

    private void enableResendNow() {
        resendTimer.cancel();
        resendEndsAt = 0;
        textResend.setEnabled(true);
        textResend.setText(R.string.action_resend);
        textResend.setTextColor(ContextCompat.getColor(this, R.color.navy_600));
    }

    // ---------------------------------------------------------------- page 3: new password

    private void updatePassword() {
        if (busy) return;
        hideFormError();
        String password = text(inputPassword);
        String confirm = text(inputConfirm);

        String passwordError = ContactValidator.isOk(ContactValidator.checkPassword(password))
                ? null : getString(R.string.error_password_short);
        String confirmError = null;
        if (confirm.isEmpty()) confirmError = getString(R.string.error_confirm_empty);
        else if (!ContactValidator.passwordsMatch(password, confirm)) confirmError = getString(R.string.error_password_mismatch);
        layoutPassword.setError(passwordError);
        layoutConfirm.setError(confirmError);
        if (passwordError != null) {
            inputPassword.requestFocus();
            return;
        }
        if (confirmError != null) {
            inputConfirm.requestFocus();
            return;
        }

        setBusy(true);
        Auth.get().setNewPassword(email, verifiedCode, password, result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
                    verifiedCode = "";
                    inputPassword.setText("");
                    inputConfirm.setText("");
                    showStep(4);
                    break;
                case NOT_VERIFIED:
                case CODE_EXPIRED:
                    verifiedCode = "";
                    showStep(2);
                    layoutCode.setError(getString(R.string.error_not_verified));
                    enableResendNow();
                    break;
                default:
                    showFormError(R.string.error_network);
            }
        });
    }

    // ---------------------------------------------------------------- navigation and state

    private void goBack() {
        if (busy) return;
        if (step == 4) {
            toSignIn();
            return;
        }
        if (step == 1) {
            finish();
            return;
        }
        // a verified token is not kept after leaving page 3, so going back starts again from the email
        resendTimer.cancel();
        verifiedCode = "";
        inputPassword.setText("");
        inputConfirm.setText("");
        showStep(1);
    }

    private void toSignIn() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showStep(int n) {
        step = n;
        findViewById(R.id.step1).setVisibility(n == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step2).setVisibility(n == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step3).setVisibility(n == 3 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step4).setVisibility(n == 4 ? View.VISIBLE : View.GONE);
        stepHeader.setVisibility(n == 4 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(i < n ? R.drawable.bg_step_on : R.drawable.bg_step_off);
        }
        textStepLabel.setText(getString(R.string.step_label_fmt, Math.min(n, 3), 3));
        if (n == 2) {
            textCodeSub.setText(getString(R.string.forgot_step2_sub_fmt,
                    ContactValidator.maskEmail(email), AuthService.CODE_VALID_MINUTES));
        }
        boolean demoVisible = showDemo && (n == 1 || n == 2);
        textDemoNote.setVisibility(demoVisible ? View.VISIBLE : View.GONE);
        textDemoNote.setText(getString(R.string.demo_reset_note_fmt, DemoAccounts.STUDENT_EMAIL, DemoAccounts.CLAIM_CODE));
        hideFormError();
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        buttonProceed.setEnabled(!b);
        buttonVerify.setEnabled(!b);
        buttonUpdate.setEnabled(!b);
        buttonBack2.setEnabled(!b);
        buttonBack3.setEnabled(!b);
    }

    private void showFormError(int resId) {
        textFormError.setText(resId);
        textFormError.setVisibility(View.VISIBLE);
    }

    private void hideFormError() {
        textFormError.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- small helpers

    private static void clearOnType(TextInputEditText field, TextInputLayout layout) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                if (layout.getError() != null) layout.setError(null);
            }
        });
    }

    private static void onDone(TextInputEditText field, Runnable action) {
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                action.run();
                return true;
            }
            return false;
        });
    }

    private void bindViews() {
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutCode = findViewById(R.id.layoutCode);
        layoutPassword = findViewById(R.id.layoutPassword);
        layoutConfirm = findViewById(R.id.layoutConfirm);
        inputEmail = findViewById(R.id.inputEmail);
        inputCode = findViewById(R.id.inputCode);
        inputPassword = findViewById(R.id.inputPassword);
        inputConfirm = findViewById(R.id.inputConfirm);
        buttonProceed = findViewById(R.id.buttonProceed);
        buttonVerify = findViewById(R.id.buttonVerify);
        buttonUpdate = findViewById(R.id.buttonUpdate);
        buttonBack2 = findViewById(R.id.buttonBack2);
        buttonBack3 = findViewById(R.id.buttonBack3);
        textResend = findViewById(R.id.textResend);
        textStepLabel = findViewById(R.id.textStepLabel);
        textCodeSub = findViewById(R.id.textCodeSub);
        textDemoNote = findViewById(R.id.textDemoNote);
        textFormError = findViewById(R.id.textFormError);
        progress = findViewById(R.id.progress);
        stepHeader = findViewById(R.id.stepHeader);
        dots[0] = findViewById(R.id.stepDot1);
        dots[1] = findViewById(R.id.stepDot2);
        dots[2] = findViewById(R.id.stepDot3);
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }
}
