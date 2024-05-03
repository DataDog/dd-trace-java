// This file includes software developed at SignalFx

package datadog.trace.instrumentation.springdata;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import datadog.trace.util.stacktrace.StackWalkerFactory;
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
    attachSpanOriginInfo(span);
    System.out.println("span.getTags() = " + span.getTags());
    System.out.println("span = " + span);
  }

  private void attachSpanOriginInfo(final AgentSpan span) {
    int[] i = {0};
    StackWalkerFactory.INSTANCE.walk(
        stream -> {
          stream
              // 3rd party detection rules go here
              .filter(
                  element ->
                      element.getClassName().startsWith("org.springframework.samples")
                          || !element.getClassName().startsWith("org.springframework")
                              && !element.getClassName().startsWith("org.apache")
                              && !element.getClassName().startsWith("java.")
                              && !element.getClassName().startsWith("javax.")
                              && !element.getClassName().startsWith("jdk."))
              .forEach(
                  stackTraceElement -> {
                    span.setTag(
                        String.format(DDTags.DD_EXIT_LOCATION_FILE, i[0]),
                        stackTraceElement.getClassName());
                    span.setTag(
                        String.format(DDTags.DD_EXIT_LOCATION_LINE, i[0]++),
                        stackTraceElement.getLineNumber());
                  });
          return null;
        });
  }
}
