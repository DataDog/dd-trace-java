// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.lang.reflect.Method;

public final class SpringDataDecorator extends ClientDecorator {
  public static final CharSequence REPOSITORY_OPERATION =
      UTF8BytesString.createConstant("repository.operation");
  public static final CharSequence SPRING_DATA = UTF8BytesString.createConstant("spring-data");
  public static final SpringDataDecorator DECORATOR = new SpringDataDecorator();

  private SpringDataDecorator() {}

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-data"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return SPRING_DATA;
  }

  public AgentSpan onOperation(final AgentSpan span, final Method method) {
    assert span != null;
    assert method != null;

    if (method != null) {
      span.setResourceName(spanNameForMethod(method));
    }
    return span;
  }
}
