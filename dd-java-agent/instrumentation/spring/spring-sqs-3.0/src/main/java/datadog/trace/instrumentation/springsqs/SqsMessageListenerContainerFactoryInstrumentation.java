package datadog.trace.instrumentation.springsqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Instrumentation for SqsMessageListenerContainerFactory to mark SqsAsyncClient instances as being
 * used for Spring SQS message listening.
 */
@AutoService(InstrumenterModule.class)
public class SqsMessageListenerContainerFactoryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SqsMessageListenerContainerFactoryInstrumentation() {
    super("spring-sqs");
  }

  @Override
  public String instrumentedType() {
    return "io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("software.amazon.awssdk.services.sqs.SqsAsyncClient", "java.lang.Boolean");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument the setSqsAsyncClient method to mark the client as being used for Spring
    transformer.applyAdvice(
        isMethod()
            .and(named("setSqsAsyncClient"))
            .and(takesArgument(0, named("software.amazon.awssdk.services.sqs.SqsAsyncClient"))),
        getClass().getName() + "$SetSqsAsyncClientAdvice");
  }

  public static class SetSqsAsyncClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) SqsAsyncClient sqsAsyncClient) {
      if (sqsAsyncClient != null) {
        // Mark this SqsAsyncClient as being used for Spring SQS
        InstrumentationContext.get(SqsAsyncClient.class, Boolean.class)
            .put(sqsAsyncClient, Boolean.TRUE);
      }
    }
  }
}
