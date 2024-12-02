package datadog.trace.instrumentation.reactor.core;

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
    return result == Flux.class || result == Mono.class;
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result instanceof Flux) {
      return ((Flux<?>) result)
          .doOnError(span::addThrowable)
          .doOnTerminate(span::finish)
          .doOnCancel(span::finish);
    } else if (result instanceof Mono) {
      return ((Mono<?>) result)
          .doOnError(span::addThrowable)
          .doOnTerminate(span::finish)
          .doOnCancel(span::finish);
    }
    return null;
  }
}
