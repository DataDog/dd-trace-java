import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Minimal {@link AsyncResponse} implementation for testing the
 * JakartaRsAsyncResponseInstrumentation advice directly (no real JAX-RS container/server involved)
 * -- only {@code resume}/{@code cancel}/{@code isSuspended} carry real semantics; everything else
 * is a no-op.
 */
public class FakeAsyncResponse implements AsyncResponse {

  private boolean suspended = true;
  private boolean cancelled = false;

  @Override
  public boolean resume(final Object response) {
    if (!suspended) {
      return false;
    }
    suspended = false;
    return true;
  }

  @Override
  public boolean resume(final Throwable response) {
    if (!suspended) {
      return false;
    }
    suspended = false;
    return true;
  }

  @Override
  public boolean cancel() {
    if (!suspended) {
      return false;
    }
    suspended = false;
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
    return suspended;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public boolean isDone() {
    return !suspended;
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
