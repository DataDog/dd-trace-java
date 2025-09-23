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
      span.setTag(DDTags.RESOURCE_NAME, context.getJobInstance().getClass()).toString();
      span.setTag(QUARTZ_TRIGGER_NAME, context.getTrigger().getKey().getName());
      span.setTag(QUARTZ_TRIGGER_GROUP, context.getTrigger().getKey().getGroup());
      span.setTag(QUARTZ_JOB_NAME, context.getTrigger().getJobKey().getName());
      span.setTag(QUARTZ_JOB_GROUP, context.getTrigger().getJobKey().getGroup());
    }
    return span;
  }
}
