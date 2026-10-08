package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Register with claim code, three pages:
 *   1. details: full name, student number, phone, NRC, email. Proceed asks the server to email a code.
 *   2. claim code: the code from the email. Valid for 10 minutes, 5 tries, Resend after 30 seconds.
 *   3. finish: programme, password, confirm password. Finish creates the account and signs in.
 *
 * All server work goes through Auth.get() (see AuthService), so connecting the real server
 * does not change this class.
 */
public class RegisterActivity extends AppCompatActivity {

    private static final String S_STEP = "step";
    private static final String S_NAME = "name";
    private static final String S_NUMBER = "number";
    private static final String S_PHONE = "phone";
    private static final String S_NRC = "nrc";
    private static final String S_EMAIL = "email";
    private static final String S_RESEND_END = "resend_end";

    private TextInputLayout layoutName, layoutNumber, layoutPhone, layoutNrc, layoutEmail,
            layoutCode, layoutPassword, layoutConfirm;
    private TextInputEditText inputName, inputNumber, inputPhone, inputNrc, inputEmail,
            inputCode, inputPassword, inputConfirm;
    private Spinner spinnerProgramme;
    private Button buttonProceed, buttonVerify, buttonFinish, buttonBack2, buttonBack3;
    private TextView textResend, textStepLabel, textCodeSub, textDemoNote, textFormError;
    private View progress;
    private final View[] dots = new View[3];

    private final ResendTimer resendTimer = new ResendTimer();
    private int step = 1;
    private boolean busy;
    private boolean showDemo;
    private AuthService.Registration details;
    private long resendEndsAt; // SystemClock.elapsedRealtime() when Resend turns on

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.register_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        bindViews();
        showDemo = getResources().getBoolean(R.bool.show_demo_hints);

        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, StudentRepository.PROGRAMMES);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProgramme.setAdapter(pAdapter);

        inputNrc.addTextChangedListener(new NrcTextWatcher(inputNrc));
        clearOnType(inputName, layoutName);
        clearOnType(inputNumber, layoutNumber);
        clearOnType(inputPhone, layoutPhone);
        clearOnType(inputNrc, layoutNrc);
        clearOnType(inputEmail, layoutEmail);
        clearOnType(inputCode, layoutCode);
        clearOnType(inputPassword, layoutPassword);
        clearOnType(inputConfirm, layoutConfirm);

        onDone(inputEmail, this::proceed);
        onDone(inputCode, this::verify);
        onDone(inputConfirm, this::finishRegistration);

        buttonProceed.setOnClickListener(v -> proceed());
        buttonVerify.setOnClickListener(v -> verify());
        buttonFinish.setOnClickListener(v -> finishRegistration());
        buttonBack2.setOnClickListener(v -> goBack());
        buttonBack3.setOnClickListener(v -> goBack());
        textResend.setOnClickListener(v -> {
            if (!busy && SystemClock.elapsedRealtime() >= resendEndsAt) sendCode(true);
        });

        if (savedInstanceState != null) {
            details = new AuthService.Registration(
                    savedInstanceState.getString(S_NAME, ""), savedInstanceState.getString(S_NUMBER, ""),
                    savedInstanceState.getString(S_PHONE, ""), savedInstanceState.getString(S_NRC, ""),
                    savedInstanceState.getString(S_EMAIL, ""));
            resendEndsAt = savedInstanceState.getLong(S_RESEND_END, 0);
            int saved = savedInstanceState.getInt(S_STEP, 1);
            // page 3 needs a verified code the server may no longer hold after a rotation, so go back to page 2
            showStep(saved == 3 ? 2 : saved);
            if (step == 2) startResendTimer((int) Math.max(0, (resendEndsAt - SystemClock.elapsedRealtime() + 999) / 1000));
        } else {
            showStep(1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(S_STEP, step);
        out.putLong(S_RESEND_END, resendEndsAt);
        if (details != null) {
            out.putString(S_NAME, details.name);
            out.putString(S_NUMBER, details.number);
            out.putString(S_PHONE, details.phone);
            out.putString(S_NRC, details.nrc);
            out.putString(S_EMAIL, details.email);
        }
    }

    @Override
    protected void onDestroy() {
        resendTimer.cancel();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- page 1

    private void proceed() {
        if (busy) return;
        hideFormError();

        String name = text(inputName).trim();
        String number = text(inputNumber);
        String phone = text(inputPhone);
        String nrc = text(inputNrc);
        String email = text(inputEmail).trim();

        TextInputLayout firstBad = null;
        firstBad = mark(layoutName, nameError(StudentValidator.checkName(name)), firstBad);
        firstBad = mark(layoutNumber, numberError(StudentValidator.checkNumber(number)), firstBad);
        firstBad = mark(layoutPhone, phoneError(ContactValidator.checkPhone(phone)), firstBad);
        firstBad = mark(layoutNrc, nrcError(ContactValidator.checkNrc(nrc)), firstBad);
        firstBad = mark(layoutEmail, emailError(ContactValidator.checkEmail(email)), firstBad);
        if (firstBad != null) {
            if (firstBad.getEditText() != null) firstBad.getEditText().requestFocus();
            return;
        }

        details = new AuthService.Registration(name, number, ContactValidator.normalisePhone(phone), nrc, email);
        sendCode(false);
    }

    /** Asks the server to email a code. Used by Proceed (first time) and Resend code. */
    private void sendCode(boolean resend) {
        setBusy(true);
        hideFormError();
        Auth.get().requestRegistrationCode(details, result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
                    Toast.makeText(this, resend ? R.string.toast_code_resent : R.string.toast_code_sent, Toast.LENGTH_SHORT).show();
                    inputCode.setText("");
                    showStep(2);
                    startResendTimer(AuthService.RESEND_SECONDS);
                    break;
                case ALREADY_REGISTERED:
                    showStep(1);
                    layoutNumber.setError(getString(R.string.error_number_registered));
                    inputNumber.requestFocus();
                    break;
                case NOT_ON_ROSTER:
                    showStep(1);
                    layoutNumber.setError(getString(R.string.error_not_on_roster));
                    inputNumber.requestFocus();
                    break;
                case EMAIL_IN_USE:
                    showStep(1);
                    layoutEmail.setError(getString(R.string.error_email_in_use));
                    inputEmail.requestFocus();
                    break;
                default:
                    showFormError(R.string.error_network);
            }
        });
    }

    // ---------------------------------------------------------------- page 2

    private void verify() {
        if (busy || details == null) return;
        hideFormError();
        String code = ContactValidator.normaliseCode(text(inputCode));
        ContactValidator.CodeResult check = ContactValidator.checkCode(code);
        if (!ContactValidator.isOk(check)) {
            layoutCode.setError(getString(check == ContactValidator.CodeResult.EMPTY
                    ? R.string.error_code_empty : R.string.error_code_invalid));
            return;
        }
        setBusy(true);
        Auth.get().verifyRegistrationCode(details.email, code, result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
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

    // ---------------------------------------------------------------- page 3

    private void finishRegistration() {
        if (busy || details == null) return;
        hideFormError();

        String password = text(inputPassword);
        String confirm = text(inputConfirm);
        TextInputLayout firstBad = null;
        firstBad = mark(layoutPassword, passwordError(ContactValidator.checkPassword(password)), firstBad);
        String confirmError = null;
        if (confirm.isEmpty()) confirmError = getString(R.string.error_confirm_empty);
        else if (!ContactValidator.passwordsMatch(password, confirm)) confirmError = getString(R.string.error_password_mismatch);
        firstBad = mark(layoutConfirm, confirmError, firstBad);
        if (firstBad != null) {
            if (firstBad.getEditText() != null) firstBad.getEditText().requestFocus();
            return;
        }

        String programme = StudentRepository.PROGRAMMES[Math.max(0, spinnerProgramme.getSelectedItemPosition())];
        setBusy(true);
        Auth.get().completeRegistration(details.email, programme, password, result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
                    createLocalStudent(programme);
                    break;
                case NOT_VERIFIED:
                case CODE_EXPIRED:
                    showStep(2);
                    layoutCode.setError(getString(R.string.error_not_verified));
                    enableResendNow();
                    break;
                default:
                    showFormError(R.string.error_network);
            }
        });
    }

    /** The server made the account; this keeps the demo roster in step and signs the student in. */
    private void createLocalStudent(String programme) {
        Student s = new Student(null, details.name, details.number, programme, StudentRepository.UNASSIGNED, true);
        s.email = details.email;
        s.phone = details.phone;
        s.nrc = details.nrc;
        StudentRepository.getInstance().add(s);

        Toast.makeText(this, R.string.toast_registered, Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, StudentHomeActivity.class);
        intent.putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, details.number);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ---------------------------------------------------------------- navigation and state

    private void goBack() {
        if (busy) return;
        if (step == 1) {
            finish();
            return;
        }
        // a verified code is not kept after leaving page 3, so going back starts the details again
        resendTimer.cancel();
        inputPassword.setText("");
        inputConfirm.setText("");
        showStep(1);
    }

    private void showStep(int n) {
        step = n;
        findViewById(R.id.step1).setVisibility(n == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step2).setVisibility(n == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step3).setVisibility(n == 3 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(i < n ? R.drawable.bg_step_on : R.drawable.bg_step_off);
        }
        textStepLabel.setText(getString(R.string.step_label_fmt, n, 3));
        if (n == 2 && details != null) {
            textCodeSub.setText(getString(R.string.register_step2_sub_fmt,
                    ContactValidator.maskEmail(details.email), AuthService.CODE_VALID_MINUTES));
        }
        textDemoNote.setVisibility(showDemo && n < 3 ? View.VISIBLE : View.GONE);
        textDemoNote.setText(getString(R.string.demo_code_note_fmt, DemoAccounts.CLAIM_CODE));
        hideFormError();
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        buttonProceed.setEnabled(!b);
        buttonVerify.setEnabled(!b);
        buttonFinish.setEnabled(!b);
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

    // ---------------------------------------------------------------- error text

    private String nameError(StudentValidator.NameResult r) {
        switch (r) {
            case OK: return null;
            case EMPTY: return getString(R.string.error_name_empty);
            case TOO_SHORT: return getString(R.string.error_name_short);
            case TOO_LONG: return getString(R.string.error_name_long);
            case HAS_DIGIT: return getString(R.string.error_name_digit);
            case INVALID_CHARS: return getString(R.string.error_name_chars);
            default: return getString(R.string.error_name_two_words);
        }
    }

    private String numberError(StudentValidator.NumberResult r) {
        switch (r) {
            case OK: return null;
            case EMPTY: return getString(R.string.error_number_empty);
            case HAS_SPACE: return getString(R.string.error_number_space);
            case NOT_DIGITS: return getString(R.string.error_number_digits);
            case WRONG_LENGTH: return getString(R.string.error_number_length, text(inputNumber).length());
            default: return getString(R.string.error_number_not_real);
        }
    }

    private String phoneError(ContactValidator.PhoneResult r) {
        return r == ContactValidator.PhoneResult.OK ? null
                : getString(r == ContactValidator.PhoneResult.EMPTY ? R.string.error_phone_empty : R.string.error_phone_invalid);
    }

    private String nrcError(ContactValidator.NrcResult r) {
        return r == ContactValidator.NrcResult.OK ? null
                : getString(r == ContactValidator.NrcResult.EMPTY ? R.string.error_nrc_empty : R.string.error_nrc_invalid);
    }

    private String emailError(ContactValidator.EmailResult r) {
        return r == ContactValidator.EmailResult.OK ? null
                : getString(r == ContactValidator.EmailResult.EMPTY ? R.string.error_email_empty : R.string.error_email_invalid);
    }

    private String passwordError(ContactValidator.PasswordResult r) {
        return r == ContactValidator.PasswordResult.OK ? null : getString(R.string.error_password_short);
    }

    // ---------------------------------------------------------------- small helpers

    /** Sets (or clears) the error and returns the first field that has one. */
    private static TextInputLayout mark(TextInputLayout layout, String error, TextInputLayout firstBad) {
        layout.setError(error);
        return (error != null && firstBad == null) ? layout : firstBad;
    }

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
        layoutName = findViewById(R.id.layoutName);
        layoutNumber = findViewById(R.id.layoutNumber);
        layoutPhone = findViewById(R.id.layoutPhone);
        layoutNrc = findViewById(R.id.layoutNrc);
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutCode = findViewById(R.id.layoutCode);
        layoutPassword = findViewById(R.id.layoutPassword);
        layoutConfirm = findViewById(R.id.layoutConfirm);
        inputName = findViewById(R.id.inputName);
        inputNumber = findViewById(R.id.inputNumber);
        inputPhone = findViewById(R.id.inputPhone);
        inputNrc = findViewById(R.id.inputNrc);
        inputEmail = findViewById(R.id.inputEmail);
        inputCode = findViewById(R.id.inputCode);
        inputPassword = findViewById(R.id.inputPassword);
        inputConfirm = findViewById(R.id.inputConfirm);
        spinnerProgramme = findViewById(R.id.spinnerProgramme);
        buttonProceed = findViewById(R.id.buttonProceed);
        buttonVerify = findViewById(R.id.buttonVerify);
        buttonFinish = findViewById(R.id.buttonFinish);
        buttonBack2 = findViewById(R.id.buttonBack2);
        buttonBack3 = findViewById(R.id.buttonBack3);
        textResend = findViewById(R.id.textResend);
        textStepLabel = findViewById(R.id.textStepLabel);
        textCodeSub = findViewById(R.id.textCodeSub);
        textDemoNote = findViewById(R.id.textDemoNote);
        textFormError = findViewById(R.id.textFormError);
        progress = findViewById(R.id.progress);
        dots[0] = findViewById(R.id.stepDot1);
        dots[1] = findViewById(R.id.stepDot2);
        dots[2] = findViewById(R.id.stepDot3);
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }
}
