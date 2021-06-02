package datadog.trace.api.gateway;

public interface Flow<T> {
  Flow.Action getAction();

  T getResult();

  interface Action {}

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
      return null;
    }

    @Override
    public R getResult() {
      return result;
    }
  }
}
