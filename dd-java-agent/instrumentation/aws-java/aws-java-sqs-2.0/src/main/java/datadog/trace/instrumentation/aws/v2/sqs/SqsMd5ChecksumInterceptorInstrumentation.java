package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public final class SqsMd5ChecksumInterceptorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    // The AWS SDK checksum interceptor reads ReceiveMessageResponse.messages() while finalizing
    // the response. Mark that internal access so we only wrap messages for application code.
    return "software.amazon.awssdk.services.sqs.internal.MessageMD5ChecksumInterceptor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("afterExecution")), getClass().getName() + "$AfterExecutionAdvice");
  }

  public static class AfterExecutionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      SqsReceiveResponseInternalAccess.enter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      SqsReceiveResponseInternalAccess.exit();
    }
  }
}
