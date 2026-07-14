package datadog.trace.instrumentation.reactorcore;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.EagerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtension;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ReactorAsyncResultExtension implements AsyncResultExtension, EagerHelper {
  static {
    AsyncResultExtensions.register(new ReactorAsyncResultExtension());
  }

  /**
   * Register the extension as an {@link AsyncResultExtension} using static class initialization.
   * <br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once under {@link
   * AsyncResultExtensions}.
   */
  public static void init() {}

  @Override
  public boolean supports(Class<?> result) {
    return Mono.class.isAssignableFrom(result) || Flux.class.isAssignableFrom(result);
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result instanceof Mono) {
      return ((Mono<?>) result)
          .doOnError(throwable -> onError(span, throwable))
          .doOnCancel(span::finish)
          .doOnSuccess(ignored -> span.finish());
    } else if (result instanceof Flux) {
      return ((Flux<?>) result)
          .doOnComplete(span::finish)
          .doOnError(throwable -> onError(span, throwable))
          .doOnCancel(span::finish);
    }
    return null;
  }

  private static void onError(AgentSpan span, Throwable throwable) {
    span.addThrowable(throwable);
    span.finish();
  }
}
