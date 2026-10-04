package zm.ac.mulungushi.registrar;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Ported from ForgotPasswordScreen, simplified to the core 3-step flow:
 * verify number + claim code, set a new password, done. Left out from the
 * prototype: the lockout-after-N-tries counter and the one-time recovery
 * code for lecturer resets — demo-only conveniences, not brief requirements.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private TextInputLayout layoutNumber, layoutClaim, layoutPassword;
    private TextInputEditText inputNumber, inputClaim, inputPassword;
    private String verifiedNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        ((TextView) findViewById(R.id.topBarTitle)).setText(R.string.forgot_title);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        layoutNumber = findViewById(R.id.layoutNumber);
        layoutClaim = findViewById(R.id.layoutClaim);
        layoutPassword = findViewById(R.id.layoutPassword);
        inputNumber = findViewById(R.id.inputNumber);
        inputClaim = findViewById(R.id.inputClaim);
        inputPassword = findViewById(R.id.inputPassword);

        String prefill = getIntent().getStringExtra(LoginActivity.EXTRA_PREFILL_NUMBER);
        if (prefill != null) inputNumber.setText(prefill);

        findViewById(R.id.buttonContinue).setOnClickListener(v -> verify());
        findViewById(R.id.buttonBack2).setOnClickListener(v -> showStep(1));
        findViewById(R.id.buttonUpdate).setOnClickListener(v -> update());
        findViewById(R.id.buttonBackToSignIn).setOnClickListener(v -> {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void verify() {
        String number = text(inputNumber).trim();
        String claim = text(inputClaim).trim();
        boolean staff = number.contains("@");

        boolean ok = staff
                ? number.equalsIgnoreCase(DemoAccounts.LECTURER_EMAIL) && claim.equalsIgnoreCase(DemoAccounts.CLAIM_CODE)
                : number.equals(StudentRepository.DEMO_NUMBER_FALLBACK) && claim.equalsIgnoreCase(DemoAccounts.CLAIM_CODE);

        TextView error = findViewById(R.id.textError);
        if (!ok) {
            error.setVisibility(View.VISIBLE);
            error.setText(R.string.forgot_error_mismatch);
            return;
        }
        error.setVisibility(View.GONE);
        verifiedNumber = number;
        showStep(2);
    }

    private void update() {
        String password = text(inputPassword);
        if (password.length() < 8) {
            layoutPassword.setError(getString(R.string.error_password_short));
            return;
        }
        layoutPassword.setError(null);
        showStep(3);
    }

    private void showStep(int step) {
        findViewById(R.id.step1).setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step2).setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.step3).setVisibility(step == 3 ? View.VISIBLE : View.GONE);
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }
}
