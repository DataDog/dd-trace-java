package datadog.trace.instrumentation.quartz;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_NAME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_NAME;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.TriggerKey;

public class QuartzDecorator extends BaseDecorator {
  public static final CharSequence SCHEDULED_CALL = UTF8BytesString.create("scheduled.call");
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
      if (context.getJobInstance() != null) {
        span.setTag(DDTags.RESOURCE_NAME, context.getJobInstance().getClass()).toString();
      }
      if (context.getTrigger() != null) {
        final TriggerKey key = context.getTrigger().getKey();
        if (key != null) {
          span.setTag(QUARTZ_TRIGGER_NAME, key.getName());
          span.setTag(QUARTZ_TRIGGER_GROUP, key.getGroup());
        }
        final JobKey jobKey = context.getJobDetail().getKey();
        if (jobKey != null) {
          span.setTag(QUARTZ_JOB_NAME, jobKey.getName());
          span.setTag(QUARTZ_JOB_GROUP, jobKey.getGroup());
        }
      }
    }
    return span;
  }
}
