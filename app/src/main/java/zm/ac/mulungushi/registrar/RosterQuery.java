package zm.ac.mulungushi.registrar;

import java.util.Locale;

/**
 * What the lecturer is looking for on the roster: search text plus two filters.
 * Immutable and plain Java, so the matching rules are unit-tested (RosterQueryTest)
 * and live in one place: StudentRepository.search() applies them, the
 * LecturerViewModel holds the current one, the screen only shows it.
 *
 *   text       part of a name (any case, any word) or of a student number;
 *              spaces in a number ("2023 0104") are ignored
 *   programme  ALL or a programme code (CS, IT, DS)
 *   group      ALL, a group code (G01...), or StudentRepository.UNASSIGNED
 *
 * The text and both filters work together (AND).
 */
final class RosterQuery {

    static final String ALL = "all";
    static final RosterQuery NONE = new RosterQuery("", ALL, ALL);

    final String text;
    final String programme;
    final String group;

    RosterQuery(String text, String programme, String group) {
        this.text = text == null ? "" : text;
        this.programme = programme == null || programme.isEmpty() ? ALL : programme;
        this.group = group == null || group.isEmpty() ? ALL : group;
    }

    RosterQuery withText(String t) { return new RosterQuery(t, programme, group); }
    RosterQuery withProgramme(String p) { return new RosterQuery(text, p, group); }
    RosterQuery withGroup(String g) { return new RosterQuery(text, programme, g); }
    /** Clear filters: keeps what is typed in the search box. */
    RosterQuery withoutFilters() { return new RosterQuery(text, ALL, ALL); }

    boolean hasText() { return !text.trim().isEmpty(); }
    boolean hasFilters() { return !ALL.equals(programme) || !ALL.equals(group); }
    /** How many filters are on (0, 1 or 2): shown on the Filters button. */
    int filterCount() { return (ALL.equals(programme) ? 0 : 1) + (ALL.equals(group) ? 0 : 1); }

    boolean matches(Student s) {
        if (s == null) return false;
        if (!ALL.equals(programme) && !programme.equals(s.programme)) return false;
        if (!ALL.equals(group)) {
            String g = s.group == null ? StudentRepository.UNASSIGNED : s.group;
            if (!group.equals(g)) return false;
        }
        return matchesText(s);
    }

    private boolean matchesText(Student s) {
        String q = text.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        String name = s.name == null ? "" : s.name.toLowerCase(Locale.ROOT);
        if (name.contains(q)) return true;
        // "2023 0104" or "2023-0104" finds 202301045
        String digits = q.replaceAll("[\\s-]", "");
        return !digits.isEmpty() && digits.chars().allMatch(Character::isDigit)
                && s.number != null && s.number.contains(digits);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RosterQuery)) return false;
        RosterQuery q = (RosterQuery) o;
        return text.equals(q.text) && programme.equals(q.programme) && group.equals(q.group);
    }

    @Override
    public int hashCode() {
        return (text.hashCode() * 31 + programme.hashCode()) * 31 + group.hashCode();
    }
}
