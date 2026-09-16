package datadog.context;

/**
 * Captures a context attached to one execution unit so it can be resumed in another.
 *
 * <p>To propagate context to a single background task:
 *
 * <pre>{@code
 * ContextContinuation continuation = Context.current().capture();
 * executor.execute(() -> {
 *   try (ContextScope scope = continuation.resume()) {
 *     // ... Context.current() here returns the captured context
 *   }
 *   // context implicitly released from continuation when resumed scope closes
 * });
 * }</pre>
 *
 * <p>If a continuation is never resumed (e.g. a task is cancelled before it runs), you must release
 * it explicitly to avoid resource leaks:
 *
 * <pre>{@code
 * ContextContinuation continuation = Context.current().capture();
 * Future<?> future = executor.submit(() -> {
 *   try (ContextScope scope = continuation.resume()) {
 *     // ...
 *   }
 * });
 * // ...
 * if (future.cancel(false)) {
 *   continuation.release(); // task will never resume, so release manually
 * }
 * }</pre>
 *
 * <p>When the same context is resumed concurrently across multiple threads, call {@link #hold()}
 * immediately after capture to prevent the first {@link #resume()} from releasing the context:
 *
 * <pre>{@code
 * ContextContinuation continuation = Context.current().capture().hold();
 * for (int i = 0; i < N; i++) {
 *   executor.execute(() -> {
 *     try (ContextScope scope = continuation.resume()) {
 *       // ...
 *     }
 *   });
 * }
 * // ...
 * continuation.release(); // remember to release the hold once all tasks are resumed/done
 * }</pre>
 */
public interface ContextContinuation {

  /**
   * Optional builder method to stop {@link #resume()} from implicitly releasing the captured
   * context. This is useful when multiple threads may concurrently resume the context. You must
   * then explicitly {@link #release() release} the context once all threads are resumed/done.
   *
   * @return this continuation, but with implicit release-after-resume turned off.
   */
  ContextContinuation hold();

  /**
   * Returns the context captured by this continuation.
   *
   * @return the captured context.
   */
  Context context();

  /**
   * Resumes the context captured by this continuation by attaching it to the current execution
   * unit. Implicitly {@link #release() releases} the captured context at the end of the resumed
   * scope, unless {@link #hold()} was called when creating the continuation.
   *
   * @return a scope to be closed when the resumed context is invalid.
   */
  ContextScope resume();

  /** Explicitly releases the context captured by this continuation. */
  void release();
}
