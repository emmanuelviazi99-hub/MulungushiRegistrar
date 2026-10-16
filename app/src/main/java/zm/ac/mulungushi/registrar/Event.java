package zm.ac.mulungushi.registrar;

/**
 * A one-time message from a ViewModel to its screen (a toast, a "couldn't refresh"
 * note). LiveData keeps its last value and hands it again to a screen recreated
 * after a rotation; take() returns the content only the first time, so the
 * message is not shown twice.
 */
final class Event<T> {

    private final T content;
    private boolean handled;

    Event(T content) {
        this.content = content;
    }

    /** The content, or null if a screen has already taken it. */
    synchronized T take() {
        if (handled) return null;
        handled = true;
        return content;
    }
}
