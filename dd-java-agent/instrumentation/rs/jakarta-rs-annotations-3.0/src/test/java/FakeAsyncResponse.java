import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal {@link AsyncResponse} implementation for testing the
 * JakartaRsAsyncResponseInstrumentation advice directly (no real JAX-RS container/server involved)
 * -- only {@code resume}/{@code cancel}/{@code isSuspended} carry real semantics; everything else
 * is a no-op.
 *
 * <p>{@code suspended} is an {@link AtomicBoolean}, not a plain field: real {@code AsyncResponse}
 * implementations guarantee that only one of a racing {@code resume()}/{@code cancel()} pair ever
 * succeeds. A plain boolean's read-then-write is not atomic, so two concurrent callers could both
 * observe "still suspended" and both report success, which would make tests exercising concurrent
 * terminal calls exercise a scenario the real advice never actually sees.
 */
public class FakeAsyncResponse implements AsyncResponse {

  private final AtomicBoolean suspended = new AtomicBoolean(true);
  private volatile boolean cancelled = false;

  @Override
  public boolean resume(final Object response) {
    return suspended.compareAndSet(true, false);
  }

  @Override
  public boolean resume(final Throwable response) {
    return suspended.compareAndSet(true, false);
  }

  @Override
  public boolean cancel() {
    if (!suspended.compareAndSet(true, false)) {
      return false;
    }
    cancelled = true;
    return true;
  }

  @Override
  public boolean cancel(final int retryAfter) {
    return cancel();
  }

  @Override
  public boolean cancel(final Date retryAfter) {
    return cancel();
  }

  @Override
  public boolean isSuspended() {
    return suspended.get();
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public boolean isDone() {
    return !suspended.get();
  }

  @Override
  public boolean setTimeout(final long time, final TimeUnit unit) {
    return true;
  }

  @Override
  public void setTimeoutHandler(final TimeoutHandler handler) {}

  @Override
  public Collection<Class<?>> register(final Class<?> callback) {
    return Collections.emptyList();
  }

  @Override
  public Map<Class<?>, Collection<Class<?>>> register(
      final Class<?> callback, final Class<?>... callbacks) {
    return Collections.emptyMap();
  }

  @Override
  public Collection<Class<?>> register(final Object callback) {
    return Collections.emptyList();
  }

  @Override
  public Map<Class<?>, Collection<Class<?>>> register(
      final Object callback, final Object... callbacks) {
    return Collections.emptyMap();
  }
}
