package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

public final class SqsReceiveResultInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("messages")), getClass().getName() + "$GetMessagesAdvice");
  }

  public static class GetMessagesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ReceiveMessageResponse result,
        @Advice.Return(readOnly = false) List<Message> messages) {
      if (messages != null && !messages.isEmpty() && !(messages instanceof TracingList)) {
        String queueUrl =
            InstrumentationContext.get(ReceiveMessageResponse.class, String.class).get(result);
        if (queueUrl != null) {
          messages = new TracingList(messages, queueUrl, result.responseMetadata().requestId());
        }
      }
    }
  }
}
