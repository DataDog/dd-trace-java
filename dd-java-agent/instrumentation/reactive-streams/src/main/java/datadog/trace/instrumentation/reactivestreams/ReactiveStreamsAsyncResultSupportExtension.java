package datadog.trace.instrumentation.reactivestreams;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator.AsyncResultSupportExtension;
import datadog.trace.bootstrap.instrumentation.reactive.PublisherState;
import org.reactivestreams.Publisher;

public class ReactiveStreamsAsyncResultSupportExtension implements AsyncResultSupportExtension {
  static {
    AsyncResultDecorator.registerExtension(new ReactiveStreamsAsyncResultSupportExtension());
  }

  private static ContextStore<Publisher, PublisherState> contextStore;

  /**
   * Register the extension as an {@link AsyncResultSupportExtension} using static class
   * initialization.<br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once to the
   * {@link AsyncResultDecorator}.
   */
  public static void initialize(ContextStore<Publisher, PublisherState> contextStore) {
    ReactiveStreamsAsyncResultSupportExtension.contextStore = contextStore;
  }

  @Override
  public boolean supports(Class<?> result) {
    boolean ret = Publisher.class.isAssignableFrom(result);
    return ret;
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result instanceof Publisher) {
      // the span will be closed then the subscriber span will finish.
      contextStore.putIfAbsent((Publisher) result, PublisherState::new).withPartnerSpan(span);
    }
    return result;
  }
}
