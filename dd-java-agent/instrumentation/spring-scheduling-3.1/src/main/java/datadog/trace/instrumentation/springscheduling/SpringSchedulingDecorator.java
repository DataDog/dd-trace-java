package datadog.trace.instrumentation.springscheduling;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

@Slf4j
public class SpringSchedulingDecorator extends BaseDecorator {
  public static final CharSequence SCHEDULED_CALL =
      UTF8BytesString.createConstant("scheduled.call");
  public static final SpringSchedulingDecorator DECORATE = new SpringSchedulingDecorator();

  private SpringSchedulingDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-scheduling"};
  }

  @Override
  protected String spanType() {
    return null;
  }

  @Override
  protected String component() {
    return "spring-scheduling";
  }

  public AgentSpan onRun(final AgentSpan span, final Runnable runnable) {
    if (runnable != null) {
      CharSequence resourceName = "";
      if (runnable instanceof ScheduledMethodRunnable) {
        final ScheduledMethodRunnable scheduledMethodRunnable = (ScheduledMethodRunnable) runnable;
        resourceName = spanNameForMethod(scheduledMethodRunnable.getMethod());
      } else {
        resourceName = spanNameForMethod(runnable.getClass(), "run");
      }
      span.setTag(DDTags.RESOURCE_NAME, resourceName);
    }
    return span;
  }
}
