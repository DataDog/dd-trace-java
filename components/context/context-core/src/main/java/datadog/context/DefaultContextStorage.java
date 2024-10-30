package datadog.context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class DefaultContextStorage implements ContextStorage {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultContextScope.class);

  private static final ThreadLocal<Context> THREAD_LOCAL_STORAGE =
      ThreadLocal.withInitial(ArrayBasedContext::empty);

  private final List<ContextListener> listeners;

  public DefaultContextStorage() {
    this.listeners = new CopyOnWriteArrayList<>();
  }

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
    DefaultContextScope scope = new DefaultContextScope(current, context);
    notifyOnAttachedFromNewScope(current, context, scope);
    return scope;
  }

  @Override
  public void addListener(@Nonnull ContextListener listener) {
    if (listener != null) {
      this.listeners.add(listener);
    }
  }

  @Override
  public void removeListener(@Nonnull ContextListener listener) {
    if (listener != null) {
      this.listeners.remove(listener);
    }
  }

  private void notifyOnAttachedFromNewScope(Context previous, Context current, ContextScope scope) {
    for (ContextListener listener : this.listeners) {
      try {
        listener.onAttachedFromNewScope(previous, current, scope);
      } catch (Throwable t) {
        LOGGER.debug("Error notifying listener", t);
      }
    }
  }

  private void notifyOnAttachedFromCloseScope(
      Context previous, Context current, ContextScope scope) {
    for (ContextListener listener : this.listeners) {
      try {
        listener.onAttachedFromCloseScope(previous, current, scope);
      } catch (Throwable t) {
        LOGGER.debug("Error notifying listener", t);
      }
    }
  }

  private class DefaultContextScope implements ContextScope {
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
      notifyOnAttachedFromCloseScope(this.current, this.previous, this);
    }
  }

  private enum NoopContextScope implements ContextScope {
    INSTANCE;

    @Override
    public void close() {}
  }
}
