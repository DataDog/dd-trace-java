package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.ContextStore;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tracks the subtasks forked by a single {@code StructuredTaskScope} (JDK 25+) so their captured
 * continuations can be released when the scope closes.
 *
 * <p>A subtask captures the active continuation in its constructor (see {@code
 * StructuredTaskScope25TaskInstrumentation}) and normally releases it when {@code
 * SubtaskImpl.run()} executes (via the {@link Runnable} instrumentation). When a subtask's thread
 * never starts (e.g. it is forked into an already-canceled scope), {@code run()} never executes and
 * the continuation would leak, keeping the whole parent trace unreported.
 *
 * <p>Sweeping this registry at scope {@code close()} — after {@code flock.close()} has joined every
 * started subtask — releases exactly those continuations that were never consumed. Doing it at
 * close (rather than at {@code fork()}) is race-free: a subtask whose thread did start has, by
 * then, already consumed its continuation in {@code run()}, so releasing it is a no-op.
 */
public final class SubtaskRegistry {

  public static final ContextStore.Factory<SubtaskRegistry> FACTORY = SubtaskRegistry::new;

  // Forks happen on the scope owner thread only, but use a concurrent queue as cheap insurance.
  private final Queue<Runnable> subtasks = new ConcurrentLinkedQueue<>();

  private SubtaskRegistry() {}

  /** Registers a forked subtask so its continuation can be released at scope close. */
  public void add(Runnable subtask) {
    this.subtasks.add(subtask);
  }

  /**
   * Releases the captured continuation of every registered subtask. Releasing a continuation that
   * was already consumed by {@code SubtaskImpl.run()} is a no-op, so this is safe to call for every
   * subtask once all started threads have terminated (at scope close).
   *
   * @param contextStore the {@link Runnable} context store holding each subtask's {@link State}.
   */
  public void cancelAll(ContextStore<Runnable, State> contextStore) {
    Runnable subtask;
    while ((subtask = this.subtasks.poll()) != null) {
      AdviceUtils.cancelTask(contextStore, subtask);
    }
  }
}
