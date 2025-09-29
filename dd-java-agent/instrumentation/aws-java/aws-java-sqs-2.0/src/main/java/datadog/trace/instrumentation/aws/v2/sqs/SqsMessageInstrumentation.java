package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import software.amazon.awssdk.services.sqs.model.Message;

@AutoService(InstrumenterModule.class)
public class SqsMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  
  public SqsMessageInstrumentation() {
    super("aws-java-sqs-2.0");
  }

  @Override
  public String instrumentedType() {
    return "software.amazon.awssdk.services.sqs.model.Message";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureActiveScope");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("software.amazon.awssdk.services.sqs.model.Message", State.class.getName());
  }

  public static class CaptureActiveScope {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureActiveScope(@Advice.This Message message) {
      AgentSpan span = activeSpan();
      if (span != null) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(span);
        InstrumentationContext.get(Message.class, State.class).put(message, state);
        System.out.println("[SQS] Captured state for SQS message: " + message.messageId() + 
                          " with span: " + span.getSpanId() + " on thread: " + Thread.currentThread().getId());
      } else {
        System.out.println("[SQS] No active span found when creating SQS message: " + 
                          message.messageId() + " on thread: " + Thread.currentThread().getId());
      }
    }
  }
}
