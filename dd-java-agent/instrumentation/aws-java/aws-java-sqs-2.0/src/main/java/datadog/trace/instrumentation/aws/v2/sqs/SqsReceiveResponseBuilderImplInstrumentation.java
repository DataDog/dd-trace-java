package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

public final class SqsReceiveResponseBuilderImplInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final String BUILDER_IMPL =
      "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse$BuilderImpl";

  @Override
  public String instrumentedType() {
    return BUILDER_IMPL;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("build"))
            .and(takesNoArguments())
            .and(
                returns(named("software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse"))),
        getClass().getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This Object builder, @Advice.Return ReceiveMessageResponse response) {
      if (response == null) {
        return;
      }
      ContextStore<Object, String> builderStore =
          InstrumentationContext.get(BUILDER_IMPL, "java.lang.String");
      String queueUrl = builderStore.get(builder);
      if (queueUrl != null) {
        // Complete the handoff from the pre-rebuild response to the final response user code
        // sees.
        InstrumentationContext.get(ReceiveMessageResponse.class, String.class)
            .put(response, queueUrl);
      }
    }
  }
}
