package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.List;
import java.util.Map;
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
    Map<String, String> contextStore = new java.util.HashMap<>(3);
    // Keep original String context for backward compatibility with TracingExecutionInterceptor
    contextStore.put(
        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse", "java.lang.String");
    contextStore.put(
        "software.amazon.awssdk.services.sqs.model.Message",
        "datadog.trace.bootstrap.instrumentation.java.concurrent.State");
    // Map queue URL to Spring management status (shared with SqsAsyncClientInstrumentation)
    contextStore.put("java.lang.String", "java.lang.Boolean");
    return contextStore;
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
          // Check if this queue URL is from a Spring-managed client
          Boolean isFromSpringClient =
              InstrumentationContext.get(String.class, Boolean.class).get(queueUrl);

          ContextStore<Message, State> messageStateStore = null;
          if (Boolean.TRUE.equals(isFromSpringClient)) {
            // Only continue span if message has been retrieved by spring-messaging.
            // Only set messageStateStore for Spring clients
            messageStateStore = InstrumentationContext.get(Message.class, State.class);
          }

          messages =
              new TracingList(
                  messageStateStore, messages, queueUrl, result.responseMetadata().requestId());
        }
      }
    }
  }
}
