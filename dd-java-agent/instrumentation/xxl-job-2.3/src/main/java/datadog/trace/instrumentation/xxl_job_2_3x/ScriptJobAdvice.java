package datadog.trace.instrumentation.xxl_job_2_3x;

import com.xxl.job.core.glue.GlueTypeEnum;
import com.xxl.job.core.handler.IJobHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.*;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobDecorator.DECORATOR;

public class ScriptJobAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope execute(@Advice.This IJobHandler jobHandler,
                                   @Advice.FieldValue("glueType") GlueTypeEnum glueType
      , @Advice.FieldValue("jobId") int jobId) {
    String operationName = glueType.getCmd() + "/id/" + jobId;
    AgentSpan span = DECORATOR.createSpan(operationName);
    span.setTag(CMD, glueType.getCmd());
    span.setTag(JOB_TYPE, JobConstants.JobType.SCRIPT_JOB);
    span.setTag(JOB_ID, jobId);
//    String jobParam = com.xxl.job.core.context.XxlJobHelper.getJobParam();
//    if (!StringUtil.isNullOrEmpty(jobParam)) {
//      span.setTag(JOB_PARAM, jobParam);
//    }
    AgentScope agentScope = activateSpan(span);
    return agentScope;
//    return activateSpan(noopSpan());
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }
    DECORATOR.error(scope, throwable);
    DECORATOR.beforeFinish(scope.span());
    scope.close();
    scope.span().finish();
  }
}
