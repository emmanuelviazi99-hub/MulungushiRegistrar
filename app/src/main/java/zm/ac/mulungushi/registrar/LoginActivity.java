package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class LoginActivity extends AppCompatActivity {

    public static final String EXTRA_PREFILL_NUMBER = "prefill_number";

    private LoginViewModel vm;
    private TextInputLayout idLayout;
    private TextInputLayout passwordLayout;
    private TextInputEditText idInput;
    private TextInputEditText passwordInput;
    private TextView formError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // the photo runs behind the bars, so the bars are transparent with light icons
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT));
        setContentView(R.layout.activity_login);

        View scroll = findViewById(R.id.scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        vm = new ViewModelProvider(this).get(LoginViewModel.class);
        idLayout = findViewById(R.id.layoutIdentity);
        passwordLayout = findViewById(R.id.layoutPassword);
        idInput = findViewById(R.id.inputIdentity);
        passwordInput = findViewById(R.id.inputPassword);
        formError = findViewById(R.id.formError);

        idInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) vm.onIdBlur();
        });
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        findViewById(R.id.buttonSignIn).setOnClickListener(v -> submit());
        findViewById(R.id.buttonForgot).setOnClickListener(v -> {
            Intent intent = new Intent(this, ForgotPasswordActivity.class);
            intent.putExtra(EXTRA_PREFILL_NUMBER, text(idInput).trim());
            startActivity(intent);
        });
        findViewById(R.id.buttonRegister).setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));

        setUpDemoNote();

        // short fade on first open only, not on every rotation
        if (savedInstanceState == null) {
            View card = findViewById(R.id.card);
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(200).start();
        }
    }

    // Runs after the fields have been restored, so restoring text after a rotation
    // is not counted as typing and does not wipe the form error.
    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                vm.onTextChanged();
                render();
            }
        };
        idInput.addTextChangedListener(watcher);
        passwordInput.addTextChangedListener(watcher);

        vm.getState().observe(this, s -> render());
        vm.getSignedIn().observe(this, this::onSignedIn);
    }

    private void submit() {
        vm.submit(text(idInput), text(passwordInput), isOnline());
    }

    /** Errors are worked out from the current text plus the flags in the ViewModel. */
    private void render() {
        LoginViewModel.UiState s = vm.getState().getValue();
        if (s == null) return;

        String id = text(idInput).trim();
        LoginValidator.Result r = LoginValidator.checkIdentity(id);
        String idError = null;
        if (r == LoginValidator.Result.EMPTY) {
            if (s.tried) idError = getString(R.string.error_id_empty);
        } else if ((s.idTouched || s.tried) && !LoginValidator.isOk(r)) {
            idError = idMessage(r, id);
        }
        setErrorIfChanged(idLayout, idError);

        boolean passwordMissing = s.tried && text(passwordInput).isEmpty();
        setErrorIfChanged(passwordLayout, passwordMissing ? getString(R.string.error_password_empty) : null);

        if (s.formError == 0) {
            formError.setVisibility(View.GONE);
        } else {
            formError.setText(s.formError);
            formError.setVisibility(View.VISIBLE);
        }
    }

    private String idMessage(LoginValidator.Result r, String id) {
        switch (r) {
            case EMAIL_INCOMPLETE: return getString(R.string.error_id_email);
            case NOT_DIGITS:       return getString(R.string.error_id_digits);
            case WRONG_LENGTH:     return getString(R.string.error_id_length, id.length());
            case NOT_REAL:         return getString(R.string.error_id_not_real);
            default:               return null;
        }
    }

    // setting the same error again would make TalkBack read it out again
    private static void setErrorIfChanged(TextInputLayout layout, String message) {
        if (!TextUtils.equals(layout.getError(), message)) layout.setError(message);
    }

    private void onSignedIn(LoginViewModel.Role role) {
        if (role == null) return;
        vm.consumeSignedIn();
        Intent intent;
        if (role == LoginViewModel.Role.LECTURER) {
            intent = new Intent(this, LecturerDashboardActivity.class);
        } else {
            intent = new Intent(this, StudentHomeActivity.class);
            intent.putExtra(StudentHomeActivity.EXTRA_STUDENT_NUMBER, text(idInput).trim());
        }
        startActivity(intent);
        finish();
    }

    private void notBuilt() {
        Toast.makeText(this, R.string.toast_not_built, Toast.LENGTH_SHORT).show();
    }

    private void setUpDemoNote() {
        boolean show = getResources().getBoolean(R.bool.show_demo_hints);
        findViewById(R.id.demoNote).setVisibility(show ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.demoLecturerEmail)).setText(DemoAccounts.LECTURER_EMAIL);
        ((TextView) findViewById(R.id.demoLecturerPassword)).setText(DemoAccounts.LECTURER_PASSWORD);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }
}
