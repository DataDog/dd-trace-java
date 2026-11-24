package datadog.trace.instrumentation.jetty12;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the Jetty 12 Handler callback so that completing the request does not cut a pending
 * blocking response write.
 *
 * <p>After a mid-handler blocking response write was accepted, the caller throws a {@code
 * BlockingException} and the ee10/ee9 layers complete the Handler callback right away. If the
 * asynchronous block write is still pending at that point, Jetty cancels it and the client receives
 * a truncated block page. While a block write is in flight this wrapper parks the first completion
 * and replays it, with the same outcome, when the write finishes. Otherwise completions are
 * forwarded unchanged.
 *
 * <p>Never waits: the race between the completion and the write callback is resolved with a single
 * CAS on {@link #state}. Only the first completion is honoured; later ones are dropped.
 */
public final class JettyDeferredCompletion implements Callback {
  private static final Logger log = LoggerFactory.getLogger(JettyDeferredCompletion.class);

  private static final AtomicReferenceFieldUpdater<JettyDeferredCompletion, Object> STATE =
      AtomicReferenceFieldUpdater.newUpdater(JettyDeferredCompletion.class, Object.class, "state");

  /** No block write in flight: completions are forwarded. */
  private static final Object IDLE = new Object();

  /** A block write is in flight: the first completion is parked. */
  private static final Object WRITING = new Object();

  /** The delegate was completed (forwarded or replayed): further completions are dropped. */
  private static final Object COMPLETED = new Object();

  /** Parked outcome of {@link #succeeded()}. */
  private static final Object PARKED_SUCCESS = new Object();

  /** Parked outcome of {@code failed(null)}. */
  private static final Object PARKED_NULL_FAILURE = new Object();

  private final Callback delegate;

  /**
   * {@link #IDLE}, {@link #WRITING}, {@link #COMPLETED}, or the parked outcome: {@link
   * #PARKED_SUCCESS}, {@link #PARKED_NULL_FAILURE} or the parked failure {@link Throwable}. Keeping
   * the outcome in the CAS field itself means two racing completions cannot overwrite each other's
   * outcome.
   */
  private volatile Object state = IDLE;

  JettyDeferredCompletion(final Callback delegate) {
    this.delegate = delegate;
  }

  @Override
  public InvocationType getInvocationType() {
    return Invocable.getInvocationType(delegate);
  }

  @Override
  public void succeeded() {
    complete(PARKED_SUCCESS);
  }

  @Override
  public void failed(final Throwable failure) {
    complete(failure == null ? PARKED_NULL_FAILURE : failure);
  }

  /**
   * Returns whether the wrapper is deferring: a block write is in flight, whether or not a
   * completion was already parked. While deferring, the delegate is completed only when the write
   * finishes. The answer can be stale by the time the caller acts on it: a write that finishes
   * meanwhile makes the next completion forward directly (or be dropped if one was replayed).
   */
  boolean isDeferring() {
    final Object current = state;
    return current != IDLE && current != COMPLETED;
  }

  /**
   * Marks a block write as in flight; must be called before the write is issued, since Jetty may
   * run the write callback inside the write call. A no-op once the delegate was completed.
   */
  void blockWriteStarted() {
    for (; ; ) {
      final Object current = state;
      if (current == IDLE) {
        if (STATE.compareAndSet(this, IDLE, WRITING)) {
          return;
        }
      } else {
        if (current != COMPLETED) {
          log.debug("Block write started while another one is in flight; ignoring");
        }
        return;
      }
    }
  }

  /**
   * Marks the block write as finished (successfully or not) and replays a parked completion, if
   * any. Runs on Jetty write callback threads: never throws.
   */
  void blockWriteFinished() {
    for (; ; ) {
      final Object current = state;
      if (current == WRITING) {
        if (STATE.compareAndSet(this, WRITING, IDLE)) {
          return;
        }
      } else if (current == IDLE || current == COMPLETED) {
        return;
      } else if (STATE.compareAndSet(this, current, COMPLETED)) {
        try {
          forward(current);
        } catch (final Throwable t) {
          // runs on Jetty write callback threads: never propagate back into Jetty
          log.debug("Failed to replay the deferred request completion", t);
        }
        return;
      }
    }
  }

  private void complete(final Object outcome) {
    for (; ; ) {
      final Object current = state;
      if (current == IDLE) {
        if (STATE.compareAndSet(this, IDLE, COMPLETED)) {
          // same thread and same exceptions as without the wrapper
          forward(outcome);
          return;
        }
      } else if (current == WRITING) {
        if (STATE.compareAndSet(this, WRITING, outcome)) {
          return;
        }
        // the write finished concurrently: retry, now forwarding directly
      } else {
        // parked or completed: only the first completion counts
        log.debug("Dropping a second completion of the request callback");
        return;
      }
    }
  }

  private void forward(final Object outcome) {
    if (outcome == PARKED_SUCCESS) {
      delegate.succeeded();
    } else if (outcome == PARKED_NULL_FAILURE) {
      delegate.failed(null);
    } else {
      delegate.failed((Throwable) outcome);
    }
  }
}
