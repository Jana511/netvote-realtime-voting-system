package backend;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Something changed" notifications - the heart of the real-time push.
 *
 * Every connected client registers a Listener:
 *   - each desktop client  (network.ClientHandler)  -> sends TCP messages
 *   - each open web page   (EventStreamHandler)     -> sends a Server-Sent Event
 *
 * PollService and SessionManager call publish() after every change (new poll,
 * vote, poll closed, user joined/left). The listeners are called on ONE
 * separate thread, so the thread that handled the vote is never slowed down
 * by sending updates. If many changes happen at once they are merged into one
 * notification (each listener always sends the latest state anyway).
 */
public class EventBus {
    public interface Listener {
        void changed();
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-dispatcher");
        t.setDaemon(true);
        return t;
    });

    public void subscribe(Listener l)   { listeners.add(l); }
    public void unsubscribe(Listener l) { listeners.remove(l); }
    public int listenerCount()          { return listeners.size(); }

    /** Tell every listener that the poll or the user list changed. Returns immediately. */
    public void publish() {
        if (!pending.compareAndSet(false, true)) return;     // a notification is already waiting
        dispatcher.execute(() -> {
            pending.set(false);
            for (Listener l : listeners) {
                try {
                    l.changed();
                } catch (RuntimeException e) {
                    System.err.println("Listener failed: " + e);   // one broken client never affects the others
                }
            }
        });
    }
}
