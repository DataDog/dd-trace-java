package datadog.trace.api.gateway;

import datadog.appsec.api.blocking.BlockingContentType;
import java.util.Collections;
import java.util.Map;

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

    class RequestBlockingAction implements Action {
      private final int statusCode;
      private final BlockingContentType blockingContentType;
      private final Map<String, String> extraHeaders;
      private final String blockId;

      public RequestBlockingAction(
          int statusCode,
          BlockingContentType blockingContentType,
          Map<String, String> extraHeaders,
          String blockId) {
        this.statusCode = statusCode;
        this.blockingContentType = blockingContentType;
        this.extraHeaders = extraHeaders;
        this.blockId = blockId;
      }

      public RequestBlockingAction(
          int statusCode,
          BlockingContentType blockingContentType,
          Map<String, String> extraHeaders) {
        this(statusCode, blockingContentType, extraHeaders, null);
      }

      public RequestBlockingAction(int statusCode, BlockingContentType blockingContentType) {
        this(statusCode, blockingContentType, Collections.emptyMap(), null);
      }

      public static RequestBlockingAction forRedirect(int statusCode, String location) {
        return new RequestBlockingAction(
            statusCode, BlockingContentType.NONE, Collections.singletonMap("Location", location), null);
      }

      public static RequestBlockingAction forRedirect(
          int statusCode, String location, String blockId) {
        return new RequestBlockingAction(
            statusCode, BlockingContentType.NONE, Collections.singletonMap("Location", location), blockId);
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

      public Map<String, String> getExtraHeaders() {
        return extraHeaders;
      }

      public String getBlockId() {
        return blockId;
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
