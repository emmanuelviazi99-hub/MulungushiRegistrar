package zm.ac.mulungushi.registrar;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.card_edit_details_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String number = getIntent().getStringExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER);
        if (number == null) number = StudentRepository.DEMO_NUMBER_FALLBACK;
        student = StudentRepository.getInstance().findOrCreateDemoStudent(number);

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
        android.widget.Toast.makeText(this, R.string.toast_changes_saved, android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showCorrectionDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText(R.string.request_correction_hint);
        hint.setTextColor(getResources().getColor(R.color.slate_500));
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, (int) (12 * getResources().getDisplayMetrics().density));
        box.addView(hint);

        EditText numberInput = new EditText(this);
        numberInput.setHint(R.string.hint_student_number);
        numberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        numberInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(9)});
        box.addView(numberInput);

        TextView error = new TextView(this);
        error.setTextColor(getResources().getColor(R.color.red_600));
        error.setTextSize(12);
        error.setVisibility(View.GONE);
        box.addView(error);

        androidx.appcompat.app.AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.request_correction_title)
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_send_request, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = numberInput.getText().toString();
                StudentValidator.NumberResult result = StudentValidator.checkNumber(value);
                if (value.equals(student.number)) {
                    error.setText(R.string.error_number_same);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                if (!StudentValidator.isOk(result)) {
                    error.setText(numberError(result, value));
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                StudentRepository.getInstance().requestNumberCorrection(student.id, value);
                dialog.dismiss();
                render();
            });
        });
        dialog.show();
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
