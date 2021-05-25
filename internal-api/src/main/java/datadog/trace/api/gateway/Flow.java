package datadog.trace.api.gateway;

public interface Flow<T> {
  Flow.Action getAction();

  T getResult();

  interface Action {}

  class ResultFlow<R> implements Flow<R> {
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
