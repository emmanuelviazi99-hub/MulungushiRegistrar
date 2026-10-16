package zm.ac.mulungushi.registrar;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The lecturer's roster list: one row per student, and at the end a "Showing 10 of 23 /
 * Show more" row while more students match than are shown (pagination, UI/UX team).
 * Rows changed on this phone and not uploaded yet say "Waiting to upload".
 * TalkBack reads each row as one sentence.
 */
public class StudentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnRowClick {
        void onClick(Student student);
    }

    private static final int TYPE_ROW = 0;
    private static final int TYPE_MORE = 1;

    private final List<Student> items = new ArrayList<>();
    private final OnRowClick onRowClick;
    private int totalMatching = 0;
    private Runnable onLoadMore;

    public StudentAdapter(OnRowClick onRowClick) {
        this.onRowClick = onRowClick;
    }

    /** Called when the "Show more" button is tapped. */
    public void setOnLoadMore(Runnable onLoadMore) {
        this.onLoadMore = onLoadMore;
    }

    public void submit(List<Student> newItems) {
        submit(newItems, newItems.size());
    }

    /** newItems is the page shown so far; total is how many students match in all. */
    public void submit(List<Student> newItems, int total) {
        items.clear();
        items.addAll(newItems);
        totalMatching = Math.max(total, newItems.size());
        notifyDataSetChanged();
    }

    private boolean hasMore() {
        return items.size() < totalMatching;
    }

    @Override
    public int getItemViewType(int position) {
        return position < items.size() ? TYPE_ROW : TYPE_MORE;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_MORE) {
            return new MoreHolder(inflater.inflate(R.layout.item_load_more, parent, false));
        }
        return new Holder(inflater.inflate(R.layout.item_student, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        if (viewHolder instanceof MoreHolder) {
            MoreHolder more = (MoreHolder) viewHolder;
            more.showing.setText(more.showing.getContext()
                    .getString(R.string.roster_showing, items.size(), totalMatching));
            more.button.setOnClickListener(v -> {
                if (onLoadMore != null) onLoadMore.run();
            });
            Accessibility.explain(more.button, R.string.why_load_more);
            return;
        }
        Holder holder = (Holder) viewHolder;
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
        holder.unsynced.setVisibility(s.unsynced ? View.VISIBLE : View.GONE);

        // TalkBack reads the row as one sentence instead of five separate pieces.
        Context c = holder.itemView.getContext();
        String spoken = c.getString(R.string.a11y_student_row,
                s.name, Accessibility.spokenDigits(s.number), s.programme, s.group);
        if (s.unsynced) spoken += ". " + c.getString(R.string.label_waiting_upload);
        holder.itemView.setContentDescription(spoken);
        holder.initials.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        holder.initials.setText(initialsOf(s.name));
        holder.itemView.setOnClickListener(v -> onRowClick.onClick(s));
        Accessibility.explainRow(holder.itemView, c.getString(R.string.why_student_row));
    }

    @Override
    public int getItemCount() {
        return items.size() + (hasMore() ? 1 : 0);
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
        final TextView initials, name, number, programme, group, unsynced;

        Holder(@NonNull View itemView) {
            super(itemView);
            initials = itemView.findViewById(R.id.textInitials);
            name = itemView.findViewById(R.id.textName);
            number = itemView.findViewById(R.id.textNumber);
            programme = itemView.findViewById(R.id.badgeProgramme);
            group = itemView.findViewById(R.id.badgeGroup);
            unsynced = itemView.findViewById(R.id.textUnsynced);
        }
    }

    static class MoreHolder extends RecyclerView.ViewHolder {
        final TextView showing;
        final View button;

        MoreHolder(@NonNull View itemView) {
            super(itemView);
            showing = itemView.findViewById(R.id.textShowing);
            button = itemView.findViewById(R.id.buttonShowMore);
        }
    }
}
