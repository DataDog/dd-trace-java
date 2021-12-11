package datadog.trace.api.gateway;

/**
 * The result of sending an event to a callback.
 *
 * @param <T> the type of the result
 */
public interface Flow<T> {
  Flow.Action getAction();

  T getResult();

  interface Action {
    boolean isBlocking();

    final class Noop implements Action {
      public static Action INSTANCE = new Noop();

      private Noop() {}

      public boolean isBlocking() {
        return false;
      }
    }

    final class Throw implements Action {
      private final Exception exception;

      public Throw(Exception exception) {
        this.exception = exception;
      }

      public boolean isBlocking() {
        return true;
      }

      public Exception getBlockingException() {
        return this.exception;
      }
    }
  }

  final class ResultFlow<R> implements Flow<R> {
    @SuppressWarnings("rawtypes")
    private static final ResultFlow EMPTY = new ResultFlow<>(null);

    @SuppressWarnings("unchecked")
    public static <R> ResultFlow<R> empty() {
      return (ResultFlow<R>) EMPTY;
    }

    private final R result;

    public ResultFlow(R result) {
      this.result = result;
    }

    @Override
    public Action getAction() {
      return Action.Noop.INSTANCE;
    }

    @Override
    public R getResult() {
      return result;
    }
  }
}
