package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static java.util.Collections.synchronizedSet;

import datadog.trace.bootstrap.ContextStore;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks the captured {@link State} of the subtasks forked by a single {@code StructuredTaskScope}
 * (JDK 25+) so their continuations can be released when the scope closes.
 *
 * <p>A subtask captures the active continuation in its constructor (see {@code
 * StructuredTaskScope25TaskInstrumentation}) and normally releases it when {@code
 * SubtaskImpl.run()} executes (via the {@link Runnable} instrumentation). When a subtask's thread
 * never starts (e.g. it is forked into an already-canceled scope, or {@code fork()} throws), {@code
 * run()} never executes and the continuation would leak, keeping the whole parent trace unreported.
 *
 * <p>A subtask's state is removed from this registry once its {@code run()} completes, so only
 * never-started subtasks remain. Sweeping this registry at scope {@code close()}, after {@code
 * flock.close()} has joined every started subtask, releases exactly those continuations that were
 * never consumed.
 */
public final class TaskScopeStateRegistry {

  public static final ContextStore.Factory<TaskScopeStateRegistry> FACTORY =
      TaskScopeStateRegistry::new;

  // States are added by the scope owner thread and removed by the subtask threads.
  private final Set<State> states = synchronizedSet(new HashSet<>());

  private TaskScopeStateRegistry() {}

  /**
   * Registers a forked subtask state to release its continuation at scope close.
   *
   * @param state the subtask {@link State} holding its captured continuation.
   */
  public void add(State state) {
    this.states.add(state);
  }

  /**
   * Unregisters a subtask state once its subtask has run and consumed its continuation.
   *
   * @param state the subtask {@link State} to unregister.
   */
  public void remove(State state) {
    this.states.remove(state);
  }

  /**
   * Releases the captured continuation of every registered subtask state. Releasing a continuation
   * that was already consumed by {@code SubtaskImpl.run()} is a no-op, so this is safe to call once
   * all started threads have terminated (at scope close).
   */
  public void cancelAll() {
    // Iterating a synchronized set requires holding its lock
    synchronized (this.states) {
      for (State state : this.states) {
        state.closeContinuation();
      }
      this.states.clear();
    }
  }
}
