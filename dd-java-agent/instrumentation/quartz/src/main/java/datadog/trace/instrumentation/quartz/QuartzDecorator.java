package datadog.trace.instrumentation.quartz;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_DETAIL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_NAME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_REFIRE_COUNT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER_ACTUAL_TIME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER_FIRED_TIME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER_TIME_DIFFERENCE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_NAME;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;

@Slf4j
public class QuartzDecorator extends BaseDecorator {
  public static final CharSequence JOB_INSTANCE = UTF8BytesString.createConstant("job.instance");
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
      span.setTag(DDTags.RESOURCE_NAME, context.getJobInstance().getClass()).toString();
      try {
        span.setTag(QUARTZ_SCHEDULER, context.getScheduler().getSchedulerName());
      } catch (SchedulerException ignore) {
      }
      span.setTag(QUARTZ_TRIGGER_NAME, context.getTrigger().getKey().getName());
      span.setTag(QUARTZ_TRIGGER_GROUP, context.getTrigger().getKey().getGroup());
      span.setTag(QUARTZ_JOB_NAME, context.getTrigger().getJobKey().getName());
      span.setTag(QUARTZ_JOB_GROUP, context.getTrigger().getJobKey().getGroup());
      span.setTag(QUARTZ_REFIRE_COUNT, context.getRefireCount());
      final String description = context.getJobDetail().getDescription();
      if (description != null) {
        span.setTag(QUARTZ_JOB_DETAIL, description);
      }
      Date scheduledFireTime = context.getScheduledFireTime();
      Date actualFireTime = context.getFireTime();
      span.setTag(QUARTZ_SCHEDULER_FIRED_TIME, scheduledFireTime.toString());
      long difference = actualFireTime.getTime() - scheduledFireTime.getTime();
      if (difference > 0) {
        span.setTag(QUARTZ_SCHEDULER_TIME_DIFFERENCE, difference);
      }
      span.setTag(QUARTZ_SCHEDULER_ACTUAL_TIME, actualFireTime.toString());
    }
    return span;
  }
}
