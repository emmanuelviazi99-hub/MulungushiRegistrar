package zm.ac.mulungushi.registrar;

import android.app.DatePickerDialog;
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

import java.util.Calendar;

/**
 * Create an account, three pages:
 *   1. details. Student: full name, student number, date of birth, phone, NRC, email
 *      (number + NRC + date of birth must match the roster a lecturer created).
 *      Lecturer: full name, phone (optional), email, employee ID (must be on the staff list).
 *      Proceed asks the server to email a code.
 *   2. code: the code from the email. Valid for 10 minutes, 5 tries, Resend after 30 seconds.
 *   3. finish: programme (students only), password, confirm password. Finish creates the
 *      account and signs in.
 *
 * All server work goes through Auth.get() (ApiAuthService).
 */
public class RegisterActivity extends AppCompatActivity {

    private static final String S_STEP = "step";
    private static final String S_STAFF = "staff";
    private static final String S_NAME = "name";
    private static final String S_NUMBER = "number";
    private static final String S_PHONE = "phone";
    private static final String S_NRC = "nrc";
    private static final String S_DOB = "dob";
    private static final String S_EMAIL = "email";
    private static final String S_EMPLOYEE_ID = "employee_id";
    private static final String S_RESEND_END = "resend_end";

    private TextInputLayout layoutName, layoutNumber, layoutDob, layoutPhone, layoutNrc, layoutEmail,
            layoutEmployeeId, layoutCode, layoutPassword, layoutConfirm;
    private TextInputEditText inputName, inputNumber, inputDob, inputPhone, inputNrc, inputEmail,
            inputEmployeeId, inputCode, inputPassword, inputConfirm;
    private Spinner spinnerProgramme;
    private Button buttonProceed, buttonVerify, buttonFinish, buttonBack2, buttonBack3;
    private TextView textResend, textStepLabel, textCodeSub, textFormError, textStep1Sub, labelPhone,
            tabStudent, tabLecturer;
    private View progress;
    private final View[] dots = new View[3];

    private final ResendTimer resendTimer = new ResendTimer();
    private int step = 1;
    private boolean busy;
    /** false = student sign-up, true = lecturer sign-up. */
    private boolean staff;
    private AuthService.Registration details;           // student, once page 1 was accepted
    private AuthService.StaffRegistration staffDetails; // lecturer, once page 1 was accepted
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

        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, StudentRepository.PROGRAMMES);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProgramme.setAdapter(pAdapter);

        inputNrc.addTextChangedListener(new NrcTextWatcher(inputNrc));
        clearOnType(inputName, layoutName);
        clearOnType(inputNumber, layoutNumber);
        clearOnType(inputDob, layoutDob);
        clearOnType(inputPhone, layoutPhone);
        clearOnType(inputNrc, layoutNrc);
        clearOnType(inputEmail, layoutEmail);
        clearOnType(inputEmployeeId, layoutEmployeeId);
        clearOnType(inputCode, layoutCode);
        clearOnType(inputPassword, layoutPassword);
        clearOnType(inputConfirm, layoutConfirm);

        onDone(inputEmail, this::proceed);
        onDone(inputEmployeeId, this::proceed);
        onDone(inputCode, this::verify);
        onDone(inputConfirm, this::finishRegistration);

        inputDob.setOnClickListener(v -> pickDateOfBirth());
        tabStudent.setOnClickListener(v -> setRole(false));
        tabLecturer.setOnClickListener(v -> setRole(true));

        buttonProceed.setOnClickListener(v -> proceed());
        buttonVerify.setOnClickListener(v -> verify());
        buttonFinish.setOnClickListener(v -> finishRegistration());
        buttonBack2.setOnClickListener(v -> goBack());
        buttonBack3.setOnClickListener(v -> goBack());
        textResend.setOnClickListener(v -> {
            if (!busy && SystemClock.elapsedRealtime() >= resendEndsAt) sendCode(true);
        });

        if (savedInstanceState != null) {
            staff = savedInstanceState.getBoolean(S_STAFF, false);
            String email = savedInstanceState.getString(S_EMAIL, null);
            if (email != null) {
                if (staff) {
                    staffDetails = new AuthService.StaffRegistration(savedInstanceState.getString(S_NAME, ""),
                            email, savedInstanceState.getString(S_EMPLOYEE_ID, ""), savedInstanceState.getString(S_PHONE, ""));
                } else {
                    details = new AuthService.Registration(
                            savedInstanceState.getString(S_NAME, ""), savedInstanceState.getString(S_NUMBER, ""),
                            savedInstanceState.getString(S_PHONE, ""), savedInstanceState.getString(S_NRC, ""),
                            savedInstanceState.getString(S_DOB, ""), email);
                }
            }
            resendEndsAt = savedInstanceState.getLong(S_RESEND_END, 0);
            setRole(staff);
            int saved = savedInstanceState.getInt(S_STEP, 1);
            if (email == null) saved = 1;
            // page 3 needs a verified code, and the server keeps that for 30 minutes, so
            // after a rotation going back to page 2 is the safe choice
            showStep(saved == 3 ? 2 : saved);
            if (step == 2) startResendTimer((int) Math.max(0, (resendEndsAt - SystemClock.elapsedRealtime() + 999) / 1000));
        } else {
            setRole(false);
            showStep(1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(S_STEP, step);
        out.putBoolean(S_STAFF, staff);
        out.putLong(S_RESEND_END, resendEndsAt);
        if (staff && staffDetails != null) {
            out.putString(S_NAME, staffDetails.name);
            out.putString(S_EMAIL, staffDetails.email);
            out.putString(S_EMPLOYEE_ID, staffDetails.employeeId);
            out.putString(S_PHONE, staffDetails.phone);
        } else if (!staff && details != null) {
            out.putString(S_NAME, details.name);
            out.putString(S_NUMBER, details.number);
            out.putString(S_PHONE, details.phone);
            out.putString(S_NRC, details.nrc);
            out.putString(S_DOB, details.dateOfBirth);
            out.putString(S_EMAIL, details.email);
        }
    }

    @Override
    protected void onDestroy() {
        resendTimer.cancel();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- page 1

    /** Shows the fields for a student or for a lecturer. Typed text is kept when switching. */
    private void setRole(boolean lecturer) {
        staff = lecturer;
        styleTab(tabStudent, !lecturer);
        styleTab(tabLecturer, lecturer);
        textStep1Sub.setText(lecturer ? R.string.register_step1_sub_staff : R.string.register_step1_sub);
        int student = lecturer ? View.GONE : View.VISIBLE;
        int teacher = lecturer ? View.VISIBLE : View.GONE;
        for (int id : new int[]{R.id.labelNumber, R.id.layoutNumber, R.id.labelDob, R.id.layoutDob,
                R.id.labelNrc, R.id.layoutNrc, R.id.labelProgramme, R.id.spinnerProgramme}) {
            findViewById(id).setVisibility(student);
        }
        findViewById(R.id.labelEmployeeId).setVisibility(teacher);
        layoutEmployeeId.setVisibility(teacher);
        labelPhone.setText(lecturer ? R.string.label_phone_optional : R.string.label_phone);
        // the keyboard's Done key belongs to the last field on the page
        inputEmail.setImeOptions(lecturer ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        hideFormError();
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active ? R.drawable.bg_segment_active : 0);
        tab.setTextColor(ContextCompat.getColor(this, active ? R.color.navy_900 : R.color.slate_500));
        tab.setSelected(active);
    }

    private void pickDateOfBirth() {
        Calendar start = Calendar.getInstance();
        String current = text(inputDob);
        if (ContactValidator.checkDateOfBirth(current, Calendar.getInstance()) == ContactValidator.DobResult.OK) {
            start.set(Integer.parseInt(current.substring(0, 4)), Integer.parseInt(current.substring(5, 7)) - 1,
                    Integer.parseInt(current.substring(8, 10)));
        } else {
            start.add(Calendar.YEAR, -20); // a typical student's age, so fewer taps to scroll
        }
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) ->
                inputDob.setText(ContactValidator.isoDate(year, month + 1, day)),
                start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        dialog.getDatePicker().setMaxDate(yesterday.getTimeInMillis());
        dialog.show();
    }

    private void proceed() {
        if (busy) return;
        hideFormError();

        String name = text(inputName).trim();
        String phone = text(inputPhone);
        String email = text(inputEmail).trim();

        TextInputLayout firstBad = null;
        firstBad = mark(layoutName, nameError(StudentValidator.checkName(name)), firstBad);
        if (staff) {
            boolean phoneGiven = !phone.trim().isEmpty();
            firstBad = mark(layoutPhone, phoneGiven ? phoneError(ContactValidator.checkPhone(phone)) : null, firstBad);
            firstBad = mark(layoutEmail, emailError(ContactValidator.checkEmail(email)), firstBad);
            String employeeId = ContactValidator.normaliseEmployeeId(text(inputEmployeeId));
            firstBad = mark(layoutEmployeeId, employeeIdError(ContactValidator.checkEmployeeId(employeeId)), firstBad);
            if (focus(firstBad)) return;
            staffDetails = new AuthService.StaffRegistration(name, email, employeeId,
                    phoneGiven ? ContactValidator.normalisePhone(phone) : "");
        } else {
            String number = text(inputNumber);
            String dob = text(inputDob);
            String nrc = text(inputNrc);
            firstBad = mark(layoutNumber, numberError(StudentValidator.checkNumber(number)), firstBad);
            firstBad = mark(layoutDob, dobError(ContactValidator.checkDateOfBirth(dob, Calendar.getInstance())), firstBad);
            firstBad = mark(layoutPhone, phoneError(ContactValidator.checkPhone(phone)), firstBad);
            firstBad = mark(layoutNrc, nrcError(ContactValidator.checkNrc(nrc)), firstBad);
            firstBad = mark(layoutEmail, emailError(ContactValidator.checkEmail(email)), firstBad);
            if (focus(firstBad)) return;
            details = new AuthService.Registration(name, number, ContactValidator.normalisePhone(phone), nrc, dob, email);
        }
        sendCode(false);
    }

    /** Asks the server to email a code. Used by Proceed (first time) and Resend code. */
    private void sendCode(boolean resend) {
        setBusy(true);
        hideFormError();
        AuthService.Callback answer = result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
                    Toast.makeText(this, resend ? R.string.toast_code_resent : R.string.toast_code_sent, Toast.LENGTH_SHORT).show();
                    inputCode.setText("");
                    showStep(2);
                    startResendTimer(AuthService.RESEND_SECONDS);
                    break;
                case RESEND_TOO_SOON:
                    // a code was sent less than 30 seconds ago and is still valid: go and enter it
                    showStep(2);
                    startResendTimer(result.retryAfterSeconds);
                    Toast.makeText(this, getString(R.string.error_resend_wait_fmt, result.retryAfterSeconds), Toast.LENGTH_SHORT).show();
                    break;
                case ALREADY_REGISTERED:
                    showStep(1);
                    if (staff) fieldError(layoutEmail, R.string.error_staff_registered);
                    else fieldError(layoutNumber, R.string.error_number_registered);
                    break;
                case NOT_ON_ROSTER:
                    showStep(1);
                    if (staff) fieldError(layoutEmployeeId, R.string.error_staff_not_listed);
                    else fieldError(layoutNumber, R.string.error_not_on_roster);
                    break;
                case EMAIL_IN_USE:
                    showStep(1);
                    fieldError(layoutEmail, R.string.error_email_in_use);
                    break;
                default:
                    showProblem(result);
            }
        };
        if (staff) Auth.get().requestStaffCode(staffDetails, answer);
        else Auth.get().requestRegistrationCode(details, answer);
    }

    // ---------------------------------------------------------------- page 2

    private void verify() {
        if (busy || currentEmail() == null) return;
        hideFormError();
        String code = ContactValidator.normaliseCode(text(inputCode));
        ContactValidator.CodeResult check = ContactValidator.checkCode(code);
        if (!ContactValidator.isOk(check)) {
            layoutCode.setError(getString(check == ContactValidator.CodeResult.EMPTY
                    ? R.string.error_code_empty : R.string.error_code_invalid));
            return;
        }
        setBusy(true);
        AuthService.Callback answer = result -> {
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
                case INVALID_DETAILS:
                    layoutCode.setError(getString(R.string.error_code_invalid));
                    break;
                default:
                    showProblem(result);
            }
        };
        if (staff) Auth.get().verifyStaffCode(currentEmail(), code, answer);
        else Auth.get().verifyRegistrationCode(currentEmail(), code, answer);
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
        if (busy || currentEmail() == null) return;
        hideFormError();

        String password = text(inputPassword);
        String confirm = text(inputConfirm);
        TextInputLayout firstBad = null;
        firstBad = mark(layoutPassword, passwordError(ContactValidator.checkPassword(password)), firstBad);
        String confirmError = null;
        if (confirm.isEmpty()) confirmError = getString(R.string.error_confirm_empty);
        else if (!ContactValidator.passwordsMatch(password, confirm)) confirmError = getString(R.string.error_password_mismatch);
        firstBad = mark(layoutConfirm, confirmError, firstBad);
        if (focus(firstBad)) return;

        setBusy(true);
        AuthService.Callback answer = result -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            switch (result.status) {
                case OK:
                    openHome();
                    break;
                case NOT_VERIFIED:
                case CODE_EXPIRED:
                    showStep(2);
                    layoutCode.setError(getString(R.string.error_not_verified));
                    enableResendNow();
                    break;
                case ALREADY_REGISTERED:
                case EMAIL_IN_USE:
                    // someone finished with these details in the meantime
                    showStep(1);
                    fieldError(layoutEmail, result.status == AuthService.Status.EMAIL_IN_USE
                            ? R.string.error_email_in_use
                            : (staff ? R.string.error_staff_registered : R.string.error_number_registered));
                    break;
                case INVALID_DETAILS:
                    layoutPassword.setError(result.message != null ? result.message : getString(R.string.error_password_short));
                    inputPassword.requestFocus();
                    break;
                default:
                    showProblem(result);
            }
        };
        if (staff) {
            Auth.get().completeStaffRegistration(currentEmail(), password, answer);
        } else {
            String programme = StudentRepository.PROGRAMMES[Math.max(0, spinnerProgramme.getSelectedItemPosition())];
            Auth.get().completeRegistration(currentEmail(), programme, password, answer);
        }
    }

    /** The account exists and Session holds it: open the right home screen. */
    private void openHome() {
        Toast.makeText(this, R.string.toast_registered, Toast.LENGTH_SHORT).show();
        Intent intent;
        if (Session.isLecturer()) {
            intent = new Intent(this, LecturerDashboardActivity.class);
        } else {
            // the server's record of this student, so the home screen shows real data
            StudentRepository.getInstance().putSignedIn(Session.toStudent());
            intent = new Intent(this, StudentHomeActivity.class);
            intent.putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, Session.studentNumber());
        }
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
        if (n == 2 && currentEmail() != null) {
            textCodeSub.setText(getString(R.string.register_step2_sub_fmt,
                    ContactValidator.maskEmail(currentEmail()), AuthService.CODE_VALID_MINUTES));
        }
        hideFormError();
    }

    /** The email the code was sent to, or null before page 1 was accepted. */
    private String currentEmail() {
        if (staff) return staffDetails == null ? null : staffDetails.email;
        return details == null ? null : details.email;
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
        buttonProceed.setEnabled(!b);
        buttonVerify.setEnabled(!b);
        buttonFinish.setEnabled(!b);
        buttonBack2.setEnabled(!b);
        buttonBack3.setEnabled(!b);
        tabStudent.setEnabled(!b);
        tabLecturer.setEnabled(!b);
    }

    /** Problems that are not about one field. */
    private void showProblem(AuthService.Result result) {
        switch (result.status) {
            case DELIVERY_FAILED: showFormError(getString(R.string.error_delivery_failed)); break;
            case RATE_LIMITED: showFormError(getString(R.string.error_rate_limited)); break;
            case NETWORK_ERROR: showFormError(getString(R.string.error_network)); break;
            case INVALID_DETAILS:
                showFormError(result.message != null ? result.message : getString(R.string.error_server));
                break;
            default: showFormError(getString(R.string.error_server));
        }
    }

    private void showFormError(String message) {
        textFormError.setText(message);
        textFormError.setVisibility(View.VISIBLE);
    }

    private void hideFormError() {
        textFormError.setVisibility(View.GONE);
    }

    private void fieldError(TextInputLayout layout, int resId) {
        layout.setError(getString(resId));
        if (layout.getEditText() != null) layout.getEditText().requestFocus();
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

    private String dobError(ContactValidator.DobResult r) {
        return r == ContactValidator.DobResult.OK ? null
                : getString(r == ContactValidator.DobResult.EMPTY ? R.string.error_dob_empty : R.string.error_dob_invalid);
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

    private String employeeIdError(ContactValidator.EmployeeIdResult r) {
        return r == ContactValidator.EmployeeIdResult.OK ? null
                : getString(r == ContactValidator.EmployeeIdResult.EMPTY
                ? R.string.error_employee_id_empty : R.string.error_employee_id_invalid);
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

    /** Moves focus to the first field with an error. Returns true if there was one. */
    private static boolean focus(TextInputLayout firstBad) {
        if (firstBad == null) return false;
        if (firstBad.getEditText() != null) firstBad.getEditText().requestFocus();
        return true;
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
        layoutDob = findViewById(R.id.layoutDob);
        layoutPhone = findViewById(R.id.layoutPhone);
        layoutNrc = findViewById(R.id.layoutNrc);
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutEmployeeId = findViewById(R.id.layoutEmployeeId);
        layoutCode = findViewById(R.id.layoutCode);
        layoutPassword = findViewById(R.id.layoutPassword);
        layoutConfirm = findViewById(R.id.layoutConfirm);
        inputName = findViewById(R.id.inputName);
        inputNumber = findViewById(R.id.inputNumber);
        inputDob = findViewById(R.id.inputDob);
        inputPhone = findViewById(R.id.inputPhone);
        inputNrc = findViewById(R.id.inputNrc);
        inputEmail = findViewById(R.id.inputEmail);
        inputEmployeeId = findViewById(R.id.inputEmployeeId);
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
        textFormError = findViewById(R.id.textFormError);
        textStep1Sub = findViewById(R.id.textStep1Sub);
        labelPhone = findViewById(R.id.labelPhone);
        tabStudent = findViewById(R.id.tabStudent);
        tabLecturer = findViewById(R.id.tabLecturer);
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
