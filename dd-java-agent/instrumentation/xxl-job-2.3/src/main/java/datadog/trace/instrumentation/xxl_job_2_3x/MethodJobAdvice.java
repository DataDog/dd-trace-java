package datadog.trace.instrumentation.xxl_job_2_3x;

import com.xxl.job.core.handler.IJobHandler;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobConstants.*;
import static datadog.trace.instrumentation.xxl_job_2_3x.JobDecorator.DECORATOR;

public class MethodJobAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope execute(@Advice.This IJobHandler jobHandler, @Advice.FieldValue("method") final Method method) {
    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
    AgentSpan span = DECORATOR.createSpan(methodName);
    span.setTag(JOB_METHOD,methodName);
    span.setTag(JOB_TYPE,JobType.METHOD_JOB);
//    String jobParam = com.xxl.job.core.context.XxlJobHelper.getJobParam();
//    if (!StringUtil.isNullOrEmpty(jobParam)){
//      span.setTag(JOB_PARAM, jobParam);
//    }
//    span.setTag(JOB_ID, com.xxl.job.core.context.XxlJobHelper.getJobId());
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
