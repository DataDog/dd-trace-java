package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.instrumentation.springwebflux.server.SpringWebfluxHttpServerDecorator.DECORATE;

import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.Consumer;
import reactor.core.publisher.SignalType;

public final class AdviceUtils {

  public static final String SPAN_ATTRIBUTE = "datadog.trace.instrumentation.springwebflux.Span";
  public static final String PARENT_SPAN_ATTRIBUTE =
      "datadog.trace.instrumentation.springwebflux.ParentSpan";

  private static final ClassValue<CharSequence> NAMES =
      GenericClassValue.of(
          type -> {
            String name = type.getName();
            int lambdaIdx = name.lastIndexOf("$$Lambda");
            if (lambdaIdx > -1) {
              return UTF8BytesString.create(
                  name.substring(name.lastIndexOf('.') + 1, lambdaIdx) + ".lambda");
            } else {
              return DECORATE.spanNameForMethod(type, "handle");
            }
          });

  public static CharSequence constructOperationName(Object handler) {
    return NAMES.get(handler.getClass());
  }

  public static class MonoSpanFinisher implements Consumer<Object> {
    private final AgentSpan span;

    public MonoSpanFinisher(AgentSpan span) {
      this.span = span;
    }

    @Override
    public void accept(Object o) {
      if (o instanceof Throwable) {
        span.addThrowable((Throwable) o);
      } else if (o instanceof SignalType && span.phasedFinish()) {
        span.publish();
      }
    }
  }
}
