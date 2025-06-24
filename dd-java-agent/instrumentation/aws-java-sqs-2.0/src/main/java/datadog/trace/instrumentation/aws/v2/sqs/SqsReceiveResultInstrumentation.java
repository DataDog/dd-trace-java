package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@AutoService(InstrumenterModule.class)
public class SqsReceiveResultInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse";
  }

  @Override
  public boolean isEnabled(Set<TargetSystem> enabledSystems) {
    return super.isEnabled(enabledSystems)
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
        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse", "java.lang.String");
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
