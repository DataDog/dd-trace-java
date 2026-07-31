package datadog.trace.bootstrap.instrumentation.reactivestreams;

import datadog.context.Context;

/**
 * Value of the {@code (Publisher, HandoffContext)} store used to hand a context from a publisher's
 * subscribe to that publisher's own subscriber (or a blocking call).
 *
 * <p>{@link #threadConfined} deposits are only adopted on the producing thread. The reactor-core
 * bridge uses them because a shared publisher — a multicast/replay {@code Sinks.Many} with several
 * consumers — is subscribed concurrently, and keying by publisher identity alone would let one
 * consumer adopt another's context. This is safe because a subscribe chain runs synchronously on
 * one thread. {@link #anyThread} deposits are adopted anywhere, for producers (resilience4j,
 * spring-webflux, spring-messaging) that attach to a unique publisher subscribed later, possibly on
 * another thread.
 */
public final class HandoffContext {

  private static final long ANY_THREAD = 0L;

  private final long threadId;
  private final Context context;

  private HandoffContext(final Context context, final long threadId) {
    this.context = context;
    this.threadId = threadId;
  }

  public static HandoffContext anyThread(final Context context) {
    return new HandoffContext(context, ANY_THREAD);
  }

  public static HandoffContext threadConfined(final Context context) {
    return new HandoffContext(context, Thread.currentThread().getId());
  }

  /** The context, or {@code null} if this is a thread-confined deposit read on another thread. */
  public Context contextForCurrentThread() {
    return threadId == ANY_THREAD || threadId == Thread.currentThread().getId() ? context : null;
  }
}
