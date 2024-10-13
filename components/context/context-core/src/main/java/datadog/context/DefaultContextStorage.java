package datadog.context;

import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultContextStorage implements ContextStorage {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultContextScope.class);

  private static final ThreadLocal<Context> THREAD_LOCAL_STORAGE =
      ThreadLocal.withInitial(ArrayBasedContext::empty);

  @Nonnull
  @Override
  public Context empty() {
    return ArrayBasedContext.empty();
  }

  @Nonnull
  @Override
  public Context current() {
    return THREAD_LOCAL_STORAGE.get();
  }

  @Nonnull
  @Override
  public ContextScope attach(@Nonnull Context context) {
    // Check invalid argument
    if (context == null) {
      return NoopContextScope.INSTANCE;
    }
    // Check already attached context
    Context current = current();
    if (current == context) {
      return NoopContextScope.INSTANCE;
    }
    // Set new context and create related scope
    THREAD_LOCAL_STORAGE.set(context);
    return new DefaultContextScope(current, context);
  }

  private static class DefaultContextScope implements ContextScope {
    private final Context previous;
    private final Context current;
    private boolean closed;

    private DefaultContextScope(Context previous, Context current) {
      this.previous = previous;
      this.current = current;
      this.closed = false;
    }

    @Override
    public void close() {
      // Ensure if closing the current scope
      if (this.closed || Context.current() != this.current) {
        LOGGER.debug("Attempt to close context scope that is not current");
        return;
      }
      // Close the scope and restore previous context
      this.closed = true;
      THREAD_LOCAL_STORAGE.set(this.previous);
    }
  }

  private enum NoopContextScope implements ContextScope {
    INSTANCE;

    @Override
    public void close() {}
  }
}
