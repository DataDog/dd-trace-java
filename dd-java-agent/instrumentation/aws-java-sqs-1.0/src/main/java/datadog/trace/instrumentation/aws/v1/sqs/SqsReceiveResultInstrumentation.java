package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class SqsReceiveResultInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.sqs.model.ReceiveMessageResult";
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        // we don't need to instrument messages when we're doing legacy AWS-SDK tracing
        && !InstrumenterConfig.get().isLegacyInstrumentationEnabled(false, "aws-sdk");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MessageExtractAdapter",
      packageName + ".SqsDecorator",
      packageName + ".TracingIterator",
      packageName + ".TracingList",
      packageName + ".TracingListIterator"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "com.amazonaws.services.sqs.model.ReceiveMessageResult", "java.lang.String");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getMessages")), getClass().getName() + "$GetMessagesAdvice");
  }

  public static class GetMessagesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ReceiveMessageResult result,
        @Advice.Return(readOnly = false) List<Message> messages) {
      if (messages != null && !messages.isEmpty() && !(messages instanceof TracingList)) {
        String queueUrl =
            InstrumentationContext.get(ReceiveMessageResult.class, String.class).get(result);
        if (queueUrl != null) {
          messages = new TracingList(messages, queueUrl);
        }
      }
    }
  }
}
