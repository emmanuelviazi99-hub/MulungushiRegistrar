package zm.ac.mulungushi.registrar;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Add/Edit sheet, ported from EditSheet in the prototype. Same rules:
 * validate name and number as the user types, block Save while the chosen
 * group is full, and show a plain-language error if the number is already
 * taken by someone else's active or removed record.
 */
public class EditStudentSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onSaved(String toastMessage);
    }

    private static final String ARG_ID = "id";

    private Listener listener;
    private Student original; // null when adding a new student
    private TextInputLayout layoutName, layoutNumber;
    private TextInputEditText inputName, inputNumber;
    private Spinner spinnerProgramme, spinnerGroup;
    private TextView groupFullError, formError, numberNote, title;
    private Button buttonSave;
    private View buttonDelete;

    public static EditStudentSheet forAdd() {
        return new EditStudentSheet();
    }

    public static EditStudentSheet forEdit(String studentId) {
        EditStudentSheet f = new EditStudentSheet();
        Bundle b = new Bundle();
        b.putString(ARG_ID, studentId);
        f.setArguments(b);
        return f;
    }

    public void show(FragmentManager fm, Listener listener) {
        this.listener = listener;
        show(fm, "edit_student");
    }

    @NonNull @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                        .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        return dialog;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable android.view.ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_edit_student, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String id = getArguments() == null ? null : getArguments().getString(ARG_ID);
        original = id == null ? null : StudentRepository.getInstance().findById(id);

        title = view.findViewById(R.id.sheetTitle);
        layoutName = view.findViewById(R.id.layoutName);
        layoutNumber = view.findViewById(R.id.layoutNumber);
        inputName = view.findViewById(R.id.inputName);
        inputNumber = view.findViewById(R.id.inputNumber);
        spinnerProgramme = view.findViewById(R.id.spinnerProgramme);
        spinnerGroup = view.findViewById(R.id.spinnerGroup);
        groupFullError = view.findViewById(R.id.groupFullError);
        formError = view.findViewById(R.id.formError);
        numberNote = view.findViewById(R.id.numberCorrectionNote);
        buttonSave = view.findViewById(R.id.buttonSave);
        buttonDelete = view.findViewById(R.id.buttonDelete);
        View buttonCancel = view.findViewById(R.id.buttonCancel);

        title.setText(original == null ? R.string.sheet_title_add : R.string.sheet_title_edit);
        buttonSave.setText(original == null ? R.string.action_add_student : R.string.action_save_changes);
        buttonDelete.setVisibility(original == null ? View.GONE : View.VISIBLE);

        setUpSpinners();

        if (original != null) {
            inputName.setText(original.name);
            inputNumber.setText(original.number);
            spinnerProgramme.setSelection(indexOf(StudentRepository.PROGRAMMES, original.programme));
            setGroupSelection(original.group);
        }

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { renderLive(); }
        };
        inputName.addTextChangedListener(watcher);
        inputNumber.addTextChangedListener(watcher);
        spinnerGroup.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id2) { renderLive(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        buttonCancel.setOnClickListener(v -> dismiss());
        buttonSave.setOnClickListener(v -> submit());
        buttonDelete.setOnClickListener(v -> {
            if (original != null) confirmDelete(original);
        });

        renderLive();
    }

    private void setUpSpinners() {
        ArrayAdapter<String> pAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, StudentRepository.PROGRAMMES);
        pAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProgramme.setAdapter(pAdapter);

        List<String> groupLabels = new ArrayList<>();
        groupLabels.add(getString(R.string.unassigned));
        StudentRepository repo = StudentRepository.getInstance();
        for (String g : StudentRepository.GROUPS) {
            int count = repo.groupCount(g);
            boolean full = count >= StudentRepository.CAPACITY
                    && (original == null || !g.equals(original.group));
            groupLabels.add(g + " — " + count + "/" + StudentRepository.CAPACITY + " filled"
                    + (full ? getString(R.string.full_suffix) : ""));
        }
        ArrayAdapter<String> gAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, groupLabels);
        gAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGroup.setAdapter(gAdapter);
    }

    private void setGroupSelection(String group) {
        if (StudentRepository.UNASSIGNED.equals(group)) {
            spinnerGroup.setSelection(0);
            return;
        }
        int idx = indexOf(StudentRepository.GROUPS, group);
        spinnerGroup.setSelection(idx < 0 ? 0 : idx + 1);
    }

    private String selectedGroup() {
        int pos = spinnerGroup.getSelectedItemPosition();
        return pos <= 0 ? StudentRepository.UNASSIGNED : StudentRepository.GROUPS[pos - 1];
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return -1;
    }

    /** Recomputes field errors from the current input, the same way the prototype derives them. */
    private void renderLive() {
        String number = text(inputNumber);
        String group = selectedGroup();

        StudentValidator.NumberResult numberResult = StudentValidator.checkNumber(number);

        String excludeId = original == null ? "" : original.id;
        Student owner = numberResult == StudentValidator.NumberResult.OK
                ? StudentRepository.getInstance().findOwnerOfNumber(number, excludeId) : null;

        // Field errors are only shown after Save is pressed (see submit()), same as the login screen.
        boolean capacityFull = !StudentRepository.UNASSIGNED.equals(group)
                && StudentRepository.getInstance().groupCount(group) >= StudentRepository.CAPACITY
                && (original == null || !group.equals(original.group));
        groupFullError.setVisibility(capacityFull ? View.VISIBLE : View.GONE);
        if (capacityFull) {
            int currentCount = StudentRepository.getInstance().groupCount(group);
            groupFullError.setText(getString(R.string.error_group_full, currentCount, StudentRepository.CAPACITY));
        }

        boolean numberEdited = original != null && numberResult == StudentValidator.NumberResult.OK
                && owner == null && !number.equals(original.number);
        numberNote.setVisibility(numberEdited ? View.VISIBLE : View.GONE);
        if (numberEdited) {
            numberNote.setText(getString(R.string.number_correction_note, original.number));
        }
    }

    private void submit() {
        String name = text(inputName).trim();
        String number = text(inputNumber);
        String programme = StudentRepository.PROGRAMMES[Math.max(0, spinnerProgramme.getSelectedItemPosition())];
        String group = selectedGroup();

        StudentValidator.NameResult nameResult = StudentValidator.checkName(name);
        StudentValidator.NumberResult numberResult = StudentValidator.checkNumber(number);
        String excludeId = original == null ? "" : original.id;
        Student owner = numberResult == StudentValidator.NumberResult.OK
                ? StudentRepository.getInstance().findOwnerOfNumber(number, excludeId) : null;
        boolean capacityFull = !StudentRepository.UNASSIGNED.equals(group)
                && StudentRepository.getInstance().groupCount(group) >= StudentRepository.CAPACITY
                && (original == null || !group.equals(original.group));

        layoutName.setError(StudentValidator.isOk(nameResult) ? null : nameError(nameResult));
        if (owner != null) {
            layoutNumber.setError(getString(R.string.error_number_taken, number, owner.name));
        } else {
            layoutNumber.setError(StudentValidator.isOk(numberResult) ? null : numberError(numberResult, number));
        }

        if (!StudentValidator.isOk(nameResult) || !StudentValidator.isOk(numberResult) || owner != null || capacityFull) {
            return;
        }

        formError.setVisibility(View.GONE);
        StudentRepository repo = StudentRepository.getInstance();
        if (original == null) {
            Student s = new Student(null, name, number, programme, group, true);
            repo.add(s);
            finishWith(getString(R.string.toast_student_added));
        } else {
            boolean numberChanged = !original.number.equals(number);
            Student updated = new Student(original.id, name, number, programme, group, true);
            updated.pendingGroup = original.pendingGroup;
            repo.update(updated);
            finishWith(getString(numberChanged ? R.string.toast_number_corrected : R.string.toast_changes_saved));
        }
    }

    private void finishWith(String message) {
        if (listener != null) listener.onSaved(message);
        dismiss();
    }

    private void confirmDelete(Student s) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_title, s.name))
                .setMessage(getString(R.string.delete_message, s.group))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove, (d, w) -> {
                    StudentRepository.getInstance().softDelete(s.id);
                    finishWith(getString(R.string.toast_record_removed));
                })
                .show();
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

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }
}
