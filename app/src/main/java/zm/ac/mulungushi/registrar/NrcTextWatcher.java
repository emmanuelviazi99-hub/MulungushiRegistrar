package zm.ac.mulungushi.registrar;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/** Puts the slashes into an NRC while the user types digits: 123456/10/1. */
final class NrcTextWatcher implements TextWatcher {

    private final EditText field;
    private boolean editing;

    NrcTextWatcher(EditText field) {
        this.field = field;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable s) {
        if (editing) return;
        String formatted = ContactValidator.formatNrc(s.toString());
        if (formatted.equals(s.toString())) return;
        editing = true;
        field.setText(formatted);
        field.setSelection(formatted.length());
        editing = false;
    }
}
