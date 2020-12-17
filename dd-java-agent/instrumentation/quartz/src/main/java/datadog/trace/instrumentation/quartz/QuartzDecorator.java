package datadog.trace.instrumentation.quartz;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;

@Slf4j
public class QuartzDecorator extends BaseDecorator {
  public static final CharSequence SCHEDULED_CALL =
      UTF8BytesString.createConstant("scheduled.call");
  public static final QuartzDecorator DECORATE = new QuartzDecorator();

  private QuartzDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"quartz"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return "quartz";
  }

  public AgentSpan onExecute(final AgentSpan span, JobExecutionContext context) {
    if (context != null) {
      span.setTag(DDTags.RESOURCE_NAME, context.getJobInstance().getClass());
      span.setTag("refire_count", context.getRefireCount());
      try {
        span.setTag("scheduler", context.getScheduler().getSchedulerName());
      } catch (SchedulerException ignore) {
      }
      span.setTag("fire_time", context.getFireTime());
      span.setTag("quartz.trigger.name", context.getTrigger().getKey().getName());
      span.setTag("quartz.trigger.group", context.getTrigger().getKey().getGroup());
      span.setTag("quartz.job.name", context.getTrigger().getJobKey().getName());
      span.setTag("quartz.job.group", context.getTrigger().getJobKey().getGroup());
      span.setTag("quartz.time.scheduledFireTime", context.getScheduledFireTime().toString());
      //  todo
      span.setTag("quartz.time.difference", 0);
      span.setTag("quartz.time.actualFireTime", context.getFireTime().toString());
    }
    return span;
  }
}
