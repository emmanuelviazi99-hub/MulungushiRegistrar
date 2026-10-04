package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Ported from RegisterScreen. Two steps: verify claim code (name, number, claim),
 * then finish (programme, password). Demo only — any name and 9-digit number pass,
 * as long as the claim code matches DemoAccounts.CLAIM_CODE, same as the prototype's
 * "any 9-digit number and any 6 to 14 character code also work" note.
 */
public class RegisterActivity extends AppCompatActivity {

    private TextInputLayout layoutName, layoutNumber, layoutClaim, layoutPassword;
    private TextInputEditText inputName, inputNumber, inputClaim, inputPassword;
    private Spinner spinnerProgramme;
    private String verifiedNumber, verifiedName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.register_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        layoutName = findViewById(R.id.layoutName);
        layoutNumber = findViewById(R.id.layoutNumber);
        layoutClaim = findViewById(R.id.layoutClaim);
        layoutPassword = findViewById(R.id.layoutPassword);
        inputName = findViewById(R.id.inputName);
        inputNumber = findViewById(R.id.inputNumber);
        inputClaim = findViewById(R.id.inputClaim);
        inputPassword = findViewById(R.id.inputPassword);
        spinnerProgramme = findViewById(R.id.spinnerProgramme);

        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, StudentRepository.PROGRAMMES);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProgramme.setAdapter(pAdapter);

        ((TextView) findViewById(R.id.textDemoNote))
                .setText(getString(R.string.demo_claim_note, DemoAccounts.CLAIM_CODE));

        findViewById(R.id.buttonContinue).setOnClickListener(v -> verifyStep1());
        findViewById(R.id.buttonBack2).setOnClickListener(v -> showStep(1));
        findViewById(R.id.buttonFinish).setOnClickListener(v -> finishRegistration());
    }

    private void verifyStep1() {
        String name = text(inputName).trim();
        String number = text(inputNumber);
        String claim = text(inputClaim).trim();

        StudentValidator.NameResult nameResult = StudentValidator.checkName(name);
        StudentValidator.NumberResult numberResult = StudentValidator.checkNumber(number);

        layoutName.setError(StudentValidator.isOk(nameResult) ? null : getString(R.string.error_name_empty));
        layoutNumber.setError(StudentValidator.isOk(numberResult) ? null : getString(R.string.error_number_empty));

        boolean claimOk = claim.length() >= 4 && claim.equalsIgnoreCase(DemoAccounts.CLAIM_CODE);
        layoutClaim.setError(claim.isEmpty() ? getString(R.string.error_claim_empty)
                : claimOk ? null : getString(R.string.error_claim_wrong));

        if (!StudentValidator.isOk(nameResult) || !StudentValidator.isOk(numberResult) || !claimOk) return;

        if (StudentRepository.getInstance().findOwnerOfNumber(number, "") != null) {
            layoutNumber.setError(getString(R.string.error_number_registered));
            return;
        }

        verifiedName = name;
        verifiedNumber = number;
        showStep(2);
    }

    private void finishRegistration() {
        String password = text(inputPassword);
        if (password.length() < 8) {
            layoutPassword.setError(getString(R.string.error_password_short));
            return;
        }
        layoutPassword.setError(null);

        String programme = StudentRepository.PROGRAMMES[Math.max(0, spinnerProgramme.getSelectedItemPosition())];
        Student s = new Student(null, verifiedName, verifiedNumber, programme, StudentRepository.UNASSIGNED, true);
        StudentRepository.getInstance().add(s);

        Toast.makeText(this, R.string.toast_registered, Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, StudentHomeActivity.class);
        intent.putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, verifiedNumber);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showStep(int step) {
        findViewById(R.id.step1).setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step2).setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.stepDot1).setBackgroundColor(getResources().getColor(R.color.melon_600));
        findViewById(R.id.stepDot2).setBackgroundColor(getResources().getColor(
                step == 2 ? R.color.melon_600 : R.color.slate_200));
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }
}
