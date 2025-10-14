package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Instrumentation for SqsAsyncClient receiveMessage calls to track when Spring-managed clients are
 * making receive operations and mark the responses accordingly.
 */
@AutoService(InstrumenterModule.class)
public class SqsAsyncClientInstrumentation extends AbstractSqsInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.DefaultSqsAsyncClient";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new java.util.HashMap<>(2);
    contextStore.put("software.amazon.awssdk.services.sqs.SqsAsyncClient", Boolean.class.getName());
    // Map queue URL to Spring management status
    contextStore.put("java.lang.String", "java.lang.Boolean");
    return contextStore;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument the receiveMessage method to map queue URLs to Spring management status
    transformer.applyAdvice(
        isMethod()
            .and(named("receiveMessage"))
            .and(
                takesArgument(
                    0, named("software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest"))),
        getClass().getName() + "$ReceiveMessageAdvice");
  }

  public static class ReceiveMessageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(
        @Advice.This SqsAsyncClient client, @Advice.Argument(0) ReceiveMessageRequest req) {

      Boolean isSpringClient =
          InstrumentationContext.get(SqsAsyncClient.class, Boolean.class).get(client);

      if (Boolean.TRUE.equals(isSpringClient)) {
        // Map the queue URL to Spring management status
        final ContextStore<String, Boolean> queueUrlFlags =
            InstrumentationContext.get(String.class, Boolean.class);
        queueUrlFlags.put(req.queueUrl(), Boolean.TRUE);
      }
    }
  }
}
