package datadog.trace.instrumentation.reactor.core;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator.AsyncResultSupportExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ReactorAsyncResultSupportExtension implements AsyncResultSupportExtension {
  static {
    AsyncResultDecorator.registerExtension(new ReactorAsyncResultSupportExtension());
  }

  /**
   * Register the extension as an {@link AsyncResultSupportExtension} using static class
   * initialization.<br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once to the
   * {@link AsyncResultDecorator}.
   */
  public static void initialize() {}

  @Override
  public boolean supports(Class<?> result) {
    return Flux.class.isAssignableFrom(result) || Mono.class.isAssignableFrom(result);
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
