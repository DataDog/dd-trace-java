package datadog.trace.agent.test.scopediag;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Ordered callback reservations with a single consumer protected by the diagnostic lifecycle lock.
 */
final class DiagnosticEventBuffer {
  private static final Runnable NOOP = () -> {};
  private static final Event CLOSED = new Event();

  private Event consumed = new Event();
  // A successful CAS reserves FIFO order before stack capture; the volatile next/action writes
  // publish the link and payload separately. Only the consumer touches consumed and draining.
  private final AtomicReference<Event> tail = new AtomicReference<>(consumed);

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "Only consumer methods access this field, while holding the lifecycle lock")
  private boolean draining;

  Event reserve() {
    Event event = new Event();
    Event previous;
    do {
      previous = tail.get();
      if (previous == CLOSED) {
        return null;
      }
    } while (!tail.compareAndSet(previous, event));
    previous.next = event;
    return event;
  }

  /** Stops admission atomically; all successful reservations remain available to the consumer. */
  void close() {
    Event previous = tail.getAndSet(CLOSED);
    if (previous != CLOSED) {
      previous.next = CLOSED;
    }
  }

  /** Closes only if no producer reserved an event since the last drain. */
  boolean closeIfEmpty() {
    if (!tail.compareAndSet(consumed, CLOSED)) {
      return false;
    }
    consumed.next = CLOSED;
    return true;
  }

  boolean hasPending() {
    Event last = tail.get();
    return consumed != last && consumed.next != CLOSED;
  }

  boolean isNext(Event event) {
    return !draining && consumed.next == event;
  }

  /** Drains one finite prefix; opportunistic consumers never wait for a producer. */
  void drain(boolean timed, long deadline, boolean waitForPublication) {
    if (draining) {
      return;
    }
    draining = true;
    try {
      Event boundary = tail.get();
      while (consumed != boundary && (!timed || System.nanoTime() - deadline < 0)) {
        Event event = consumed.next;
        if (event == CLOSED) {
          return;
        }
        Runnable action = event == null ? null : event.action;
        if (action == null) {
          if (!waitForPublication) {
            return;
          }
          // Only control/report threads wait. Callback threads always use non-waiting drains and
          // tryLock, so holding the lifecycle lock here cannot block a carrier callback.
          LockSupport.parkNanos(this, 100_000);
          continue;
        }
        consumed.next = null;
        consumed = event;
        event.action = NOOP;
        action.run();
      }
    } finally {
      draining = false;
    }
  }

  static final class Event {
    private volatile Event next;
    private volatile Runnable action;

    void publish(Runnable action) {
      this.action = action;
    }

    void publishIfAbsent() {
      if (action == null) {
        action = NOOP;
      }
    }
  }
}
