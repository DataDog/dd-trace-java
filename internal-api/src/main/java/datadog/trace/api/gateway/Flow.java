package datadog.trace.api.gateway;

public interface Flow<T> {
  Flow.Action getAction();

  T getResult();

  interface Action {
    boolean isBlocking();

    enum Noop implements Action {
      INSTANCE;

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

    final class ForcedReturnValue implements Action {
      private final Object retVal;
      private final boolean blocking;

      public ForcedReturnValue(Object retVal, boolean blocking) {
        this.retVal = retVal;
        this.blocking = blocking;
      }

      public boolean isBlocking() {
        return blocking;
      }

      public Object getRetVal() {
        return retVal;
      }
    }

    final class ReplacedArguments implements Action {
      private final Object[] newArguments;
      private final boolean blocking;

      public ReplacedArguments(Object[] newArguments, boolean blocking) {
        this.newArguments = newArguments;
        this.blocking = blocking;
      }

      public Object[] getNewArguments() {
        return newArguments;
      }

      @Override
      public boolean isBlocking() {
        return this.blocking;
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
      return null;
    }

    @Override
    public R getResult() {
      return result;
    }
  }
}
