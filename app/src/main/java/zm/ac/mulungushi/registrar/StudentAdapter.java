package zm.ac.mulungushi.registrar;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class StudentAdapter extends RecyclerView.Adapter<StudentAdapter.Holder> {

    public interface OnRowClick {
        void onClick(Student student);
    }

    private final List<Student> items = new ArrayList<>();
    private final OnRowClick onRowClick;

    public StudentAdapter(OnRowClick onRowClick) {
        this.onRowClick = onRowClick;
    }

    public void submit(List<Student> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_student, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Student s = items.get(position);
        holder.name.setText(s.name);
        holder.number.setText(s.number);
        holder.programme.setText(s.programme);
        holder.group.setText(s.group);
        if (StudentRepository.UNASSIGNED.equals(s.group)) {
            holder.group.setBackgroundResource(R.drawable.bg_badge_red);
            holder.group.setTextColor(ContextCompat.getColor(holder.group.getContext(), R.color.red_600));
        } else {
            holder.group.setBackgroundResource(R.drawable.bg_badge_melon);
            holder.group.setTextColor(ContextCompat.getColor(holder.group.getContext(), R.color.melon_600));
        }
        holder.initials.setText(initialsOf(s.name));
        holder.itemView.setOnClickListener(v -> onRowClick.onClick(s));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String initialsOf(String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) return "?";
        String[] parts = t.split("\\s+");
        String first = String.valueOf(parts[0].charAt(0));
        if (parts.length > 1) return (first + parts[parts.length - 1].charAt(0)).toUpperCase();
        return first.toUpperCase();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView initials, name, number, programme, group;

        Holder(@NonNull View itemView) {
            super(itemView);
            initials = itemView.findViewById(R.id.textInitials);
            name = itemView.findViewById(R.id.textName);
            number = itemView.findViewById(R.id.textNumber);
            programme = itemView.findViewById(R.id.badgeProgramme);
            group = itemView.findViewById(R.id.badgeGroup);
        }
    }
}
