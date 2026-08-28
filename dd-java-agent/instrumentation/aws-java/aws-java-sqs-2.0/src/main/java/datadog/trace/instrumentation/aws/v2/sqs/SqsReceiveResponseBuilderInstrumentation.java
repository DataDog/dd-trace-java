package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

public final class SqsReceiveResponseBuilderInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final String BUILDER_IMPL =
      "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse$BuilderImpl";

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("toBuilder"))
            .and(takesNoArguments())
            .and(
                returns(
                    named(
                        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse$Builder"))),
        getClass().getName() + "$ToBuilderAdvice");
  }

  public static class ToBuilderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ReceiveMessageResponse response, @Advice.Return Object builder) {
      if (builder == null) {
        return;
      }
      String queueUrl =
          InstrumentationContext.get(ReceiveMessageResponse.class, String.class).get(response);
      if (queueUrl != null) {
        // AWS SDK core finalizes modeled responses through response.toBuilder().build().
        // Carry queueUrl onto the builder so it survives that response instance change.
        InstrumentationContext.get(BUILDER_IMPL, "java.lang.String").put(builder, queueUrl);
      }
    }
  }
}
