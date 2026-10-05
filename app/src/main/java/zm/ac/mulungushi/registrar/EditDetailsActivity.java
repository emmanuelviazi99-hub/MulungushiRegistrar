package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Ported from EditDetailsScreen + NumberCorrectionSheet. A student can save
 * their own name and programme directly (brief: "Edit your own name and
 * programme"), but the number itself is locked — they can only submit a
 * correction request for a lecturer to approve in RequestsActivity.
 */
public class EditDetailsActivity extends AppCompatActivity {

    private Student student;
    private TextInputLayout layoutName;
    private TextInputEditText inputName;
    private Spinner spinnerProgramme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_details);
        ConnectivityBanner.attach(this);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.card_edit_details_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String number = getIntent().getStringExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER);
        if (number == null) number = StudentRepository.DEMO_NUMBER_FALLBACK;
        student = StudentRepository.getInstance().findOrCreateDemoStudent(number);
        RosterFormat.bindAvatar(this, student.name, SignOutSheet.studentDetail(this, student.number));
        BottomNav.bindStudent(this, BottomNav.HOME, student.number);
        RosterFormat.stagger(this);

        layoutName = findViewById(R.id.layoutName);
        inputName = findViewById(R.id.inputName);
        spinnerProgramme = findViewById(R.id.spinnerProgramme);

        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, StudentRepository.PROGRAMMES);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProgramme.setAdapter(pAdapter);

        inputName.setText(student.name);
        for (int i = 0; i < StudentRepository.PROGRAMMES.length; i++) {
            if (StudentRepository.PROGRAMMES[i].equals(student.programme)) spinnerProgramme.setSelection(i);
        }

        findViewById(R.id.buttonCancel).setOnClickListener(v -> finish());
        findViewById(R.id.buttonSave).setOnClickListener(v -> save());
        findViewById(R.id.buttonRequestCorrection).setOnClickListener(v -> showCorrectionDialog());
        findViewById(R.id.buttonCancelNumberRequest).setOnClickListener(v -> {
            StudentRepository.getInstance().cancelNumberCorrection(student.id);
            render();
            Feedback.show(this, R.string.toast_request_cancelled_dot);
        });

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        ((TextView) findViewById(R.id.textNumberLocked)).setText(student.number);

        boolean hasPending = student.pendingNumber != null;
        findViewById(R.id.numberPendingBlock).setVisibility(hasPending ? View.VISIBLE : View.GONE);
        findViewById(R.id.requestCorrectionBlock).setVisibility(hasPending ? View.GONE : View.VISIBLE);
        if (hasPending) {
            ((TextView) findViewById(R.id.textNumberCorrectionTo))
                    .setText(getString(R.string.number_correction_to_fmt, student.pendingNumber));
        }
    }

    private void save() {
        String name = inputName.getText() == null ? "" : inputName.getText().toString().trim();
        String programme = StudentRepository.PROGRAMMES[Math.max(0, spinnerProgramme.getSelectedItemPosition())];
        StudentValidator.NameResult result = StudentValidator.checkName(name);

        if (!StudentValidator.isOk(result)) {
            layoutName.setError(nameError(result));
            return;
        }
        layoutName.setError(null);

        Student updated = student.copy();
        updated.name = name;
        updated.programme = programme;
        StudentRepository.getInstance().update(updated);
        Feedback.postForNext(getString(R.string.toast_changes_saved_dot));
        finish();
    }

    private void showCorrectionDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_number_correction, null);
        dialog.setContentView(content);
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.4f);
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        TextInputLayout numberLayout = content.findViewById(R.id.layoutCorrectNumber);
        TextInputEditText numberInput = content.findViewById(R.id.inputCorrectNumber);
        TextInputEditText reasonInput = content.findViewById(R.id.inputReason);

        content.findViewById(R.id.buttonSheetCancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.buttonSheetSend).setOnClickListener(v -> {
            String value = numberInput.getText() == null ? "" : numberInput.getText().toString().trim();
            String reason = reasonInput.getText() == null ? "" : reasonInput.getText().toString().trim();
            StudentValidator.NumberResult result = StudentValidator.checkNumber(value);
            if (value.equals(student.number)) {
                numberLayout.setError(getString(R.string.error_number_same));
                return;
            }
            if (!StudentValidator.isOk(result)) {
                numberLayout.setError(numberError(result, value));
                return;
            }
            numberLayout.setError(null);
            StudentRepository.getInstance().requestNumberCorrection(student.id, value, reason);
            dialog.dismiss();
            render();
            Feedback.show(this, R.string.toast_request_sent_pending);
            if (Notifier.ASK.equals(Notifier.pref)) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> Notifier.showSheet(this), 1200);
            }
        });
        numberInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { numberLayout.setError(null); }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        dialog.show();
        numberInput.requestFocus();
    }

    private String nameError(StudentValidator.NameResult r) {
        switch (r) {
            case EMPTY: return getString(R.string.error_name_empty);
            case TOO_SHORT: return getString(R.string.error_name_short);
            case TOO_LONG: return getString(R.string.error_name_long);
            case HAS_DIGIT: return getString(R.string.error_name_digit);
            case INVALID_CHARS: return getString(R.string.error_name_chars);
            case NEED_TWO_WORDS: return getString(R.string.error_name_two_words);
            default: return null;
        }
    }

    private String numberError(StudentValidator.NumberResult r, String value) {
        switch (r) {
            case EMPTY: return getString(R.string.error_number_empty);
            case HAS_SPACE: return getString(R.string.error_number_space);
            case NOT_DIGITS: return getString(R.string.error_number_digits);
            case WRONG_LENGTH: return getString(R.string.error_number_length, value.length());
            case NOT_REAL: return getString(R.string.error_number_not_real);
            default: return null;
        }
    }
}
