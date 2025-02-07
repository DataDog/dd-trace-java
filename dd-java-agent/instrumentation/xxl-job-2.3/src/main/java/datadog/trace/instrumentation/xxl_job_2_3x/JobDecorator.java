package datadog.trace.instrumentation.xxl_job_2_3x;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.JOB_CODE;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.XXL_JOB_REQUEST;

public class JobDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(JobDecorator.class);
  public static final JobDecorator DECORATOR = new JobDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{JobConstants.INSTRUMENTATION_NAME};
  }

  @Override
  protected CharSequence spanType() {
    return JobConstants.XXL_JOB_SERVER;
  }

  @Override
  protected CharSequence component() {
    return JobConstants.XXL_JOB_SERVER;
  }

  public AgentSpan createSpan(String operationName) {
    AgentSpan span = startSpan(XXL_JOB_REQUEST);
    withMethod(span, operationName);
    afterStart(span);
    return span;
  }

  public void withMethod(final AgentSpan span, final String methodName) {
    span.setResourceName(methodName);
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    return super.afterStart(span);
  }

  public AgentScope error(AgentScope scope, Throwable throwable) {
//    scope.span().setTag(JOB_CODE,XxlJobContext.getXxlJobContext().getHandleCode());
//    if (XxlJobContext.getXxlJobContext().getHandleCode() > XxlJobContext.HANDLE_COCE_SUCCESS) {
//      throwable = new Throwable(XxlJobContext.getXxlJobContext().getHandleMsg());
//    }
    return super.onError(scope,throwable);
  }
}
