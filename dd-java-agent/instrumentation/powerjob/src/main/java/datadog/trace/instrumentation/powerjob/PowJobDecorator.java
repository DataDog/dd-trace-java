package datadog.trace.instrumentation.powerjob;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import tech.powerjob.worker.core.processor.TaskContext;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

public class PowJobDecorator extends BaseDecorator {

  public static final PowJobDecorator DECORATOR = new PowJobDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{PowerjobConstants.INSTRUMENTATION_NAME};
  }

  @Override
  protected CharSequence spanType() {
    return "powerjob";
  }

  @Override
  protected CharSequence component() {
    return "powerjob";
  }

  public AgentSpan createSpan(String operationName, TaskContext context,String processType) {
    AgentSpan span = startSpan(operationName);

    span.setTag(PowerjobConstants.Tags.JOB_ID,context.getJobId());
    span.setTag(PowerjobConstants.Tags.INSTANCE_ID,context.getInstanceId());
    span.setTag(PowerjobConstants.Tags.JOB_PARAM,context.getJobParams());
    span.setTag(PowerjobConstants.Tags.TASK_NAME,context.getTaskName());
    span.setTag(PowerjobConstants.Tags.TASK_ID,context.getTaskId());
    span.setTag(PowerjobConstants.Tags.PROCESS_TYPE,processType);
    afterStart(span);
    return span;
  }
}
