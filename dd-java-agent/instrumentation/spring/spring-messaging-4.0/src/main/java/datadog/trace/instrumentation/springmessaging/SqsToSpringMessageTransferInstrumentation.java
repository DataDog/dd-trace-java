package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.TreeMap;
import net.bytebuddy.asm.Advice;
import org.springframework.messaging.Message;

@AutoService(InstrumenterModule.class)
public class SqsToSpringMessageTransferInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  
  public SqsToSpringMessageTransferInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public String instrumentedType() {
    return "io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Try to instrument toMessagingMessage method
    transformer.applyAdvice(
        named("toMessagingMessage"),
        getClass().getName() + "$TransferState");
    
    // Instrument constructor to see if class is being instantiated
    transformer.applyAdvice(
        isConstructor(),
        getClass().getName() + "$ConstructorAdvice");
    
    // Also try to instrument any method to see what's available
    transformer.applyAdvice(
        net.bytebuddy.matcher.ElementMatchers.any(),
        getClass().getName() + "$AnyMethodAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new TreeMap<>();
    // contextStore.put("Object", State.class.getName());
    // contextStore.put("org.springframework.messaging.Message", State.class.getName());
    return contextStore;
  }

  public static class TransferState {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void transfer(
        @Advice.Argument(0) Object sqsMessage,
        @Advice.Return Message springMessage) {
       System.out.println("[SQS->Spring] Transferred state from SQS message to Spring message on thread: " + 
            Thread.currentThread().getId());

      //if (null != sqsMessage && null != springMessage && 
      //    sqsMessage.getClass().getName().equals("software.amazon.awssdk.services.sqs.model.Message")) {
      //  ContextStore<Object, State> from =
      //      InstrumentationContext.get(Object.class, State.class);
      //  State state = from.get(sqsMessage);
      //  if (null != state) {
      //    from.put(sqsMessage, null);
      //    InstrumentationContext.get(Message.class, State.class).put(springMessage, state);
      //    System.out.println("[SQS->Spring] Transferred state from SQS message to Spring message on thread: " + 
      //                      Thread.currentThread().getId());
      //  } else {
      //    System.out.println("[SQS->Spring] No state found in SQS message during conversion on thread: " + 
      //                      Thread.currentThread().getId());
      //  }
      //} else {
      //  System.out.println("[SQS->Spring] Skipping transfer - not an SQS message or null message on thread: " + 
      //                    Thread.currentThread().getId());
      //}
    }
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstructor(@Advice.This Object converter) {
      System.out.println("[SQS->Spring] SqsMessagingMessageConverter constructor called on thread: " + 
                        Thread.currentThread().getId() + " - " + converter.getClass().getName());
    }
  }

  public static class AnyMethodAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin String method) {
      System.out.println("[SQS->Spring] Method called: " + method + " on thread: " + Thread.currentThread().getId());
    }
    
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Origin String method) {
      System.out.println("[SQS->Spring] Method completed: " + method + " on thread: " + Thread.currentThread().getId());
    }
  }
}
