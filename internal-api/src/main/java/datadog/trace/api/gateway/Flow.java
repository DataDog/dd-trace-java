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

    class Noop implements Action {
      public static Action INSTANCE = new Noop();

      private Noop() {}

      public boolean isBlocking() {
        return false;
      }
    }

    enum BlockingContentType {
      AUTO,
      HTML,
      JSON,
    }

    class RequestBlockingAction implements Action {
      private final int statusCode;

      private final BlockingContentType blockingContentType;

      public RequestBlockingAction(int statusCode, BlockingContentType blockingContentType) {
        this.statusCode = statusCode;
        this.blockingContentType = blockingContentType;
      }

      @Override
      public boolean isBlocking() {
        return true;
      }

      public int getStatusCode() {
        return statusCode;
      }

      public BlockingContentType getBlockingContentType() {
        return blockingContentType;
      }
    }
  }

  class ResultFlow<R> implements Flow<R> {
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
