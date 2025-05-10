package datadog.context;

/** {@link ContextManager} that uses a {@link ThreadLocal} to track context per thread. */
final class ThreadLocalContextManager implements ContextManager {
  static final ContextManager INSTANCE = new ThreadLocalContextManager();

  private static final ThreadLocal<Context[]> CURRENT_HOLDER =
      ThreadLocal.withInitial(() -> new Context[] {EmptyContext.INSTANCE});

  @Override
  public Context current() {
    return CURRENT_HOLDER.get()[0];
  }

  @Override
  public ContextScope attach(Context context) {
    Context[] holder = CURRENT_HOLDER.get();
    Context previous = holder[0];
    holder[0] = context;
    return new ContextScope() {
      private boolean closed;

      @Override
      public Context context() {
        return context;
      }

      @Override
      public void close() {
        if (!closed && context == holder[0]) {
          holder[0] = previous;
          closed = true;
        }
      }
    };
  }

  @Override
  public Context swap(Context context) {
    Context[] holder = CURRENT_HOLDER.get();
    Context previous = holder[0];
    holder[0] = context;
    return previous;
  }
}
