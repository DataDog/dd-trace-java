// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;
import static datadog.trace.bootstrap.debugger.DebuggerContext.marker;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

public final class SpringDataDecorator extends ClientDecorator {
  public static final CharSequence REPOSITORY_OPERATION =
      UTF8BytesString.create("repository.operation");
  public static final CharSequence SPRING_DATA = UTF8BytesString.create("spring-data");
  public static final SpringDataDecorator DECORATOR = new SpringDataDecorator();

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

  public void onOperation(
      final AgentSpan span, final Method method, @Nullable final Class<?> repositoryIntf) {
    assert span != null;
    assert method != null;
    if (repositoryIntf != null && Config.get().isSpringDataRepositoryInterfaceResourceName()) {
      span.setResourceName(spanNameForMethod(repositoryIntf, method));
    } else {
      span.setResourceName(spanNameForMethod(method));
    }

    marker();
    captureCodeOrigin(false);
  }
}
