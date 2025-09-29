package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
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
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.messaging.Message;

// @AutoService(InstrumenterModule.class)  // Temporarily disabled to test SqsToSpringMessageTransferInstrumentation
public class AbstractMessageConvertingMessageSourceToMessagingInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AbstractMessageConvertingMessageSourceToMessagingInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.awspring.cloud.sqs.support.converter.AbstractMessagingMessageConverter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Instrument toMessagingMessage method
    transformer.applyAdvice(
        named("toMessagingMessage"),
        getClass().getName() + "$ToMessagingMessageAdvice");
    
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new TreeMap<>();
    // contextStore.put("Object", State.class.getName());
    // contextStore.put("org.springframework.messaging.Message", State.class.getName());
    return contextStore;
  }

  public static class ToMessagingMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object sqsMessage, @Advice.This Object converter) {
      System.out.println("[ToMessaging] toMessagingMessage called with SQS message: " + 
                        sqsMessage + " on thread: " + Thread.currentThread().getId());
      
      // Print the actual child class being used
      System.out.println("[ToMessaging] Converter class: " + converter.getClass().getName());
      System.out.println("[ToMessaging] Converter class hierarchy:");
      Class<?> currentClass = converter.getClass();
      int level = 0;
      while (currentClass != null && level < 3) {
        System.out.println("[ToMessaging]   Level " + level + ": " + currentClass.getName());
        currentClass = currentClass.getSuperclass();
        level++;
      }
      
      // Print stack trace to see the call flow
      System.out.println("[ToMessaging] Stack trace:");
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
      for (int i = 0; i < Math.min(stackTrace.length, 15); i++) {
        System.out.println("[ToMessaging]   at " + stackTrace[i]);
      }
    }
    
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) Object sqsMessage,
        @Advice.Return Message springMessage) {
      System.out.println("[ToMessaging] toMessagingMessage completed - SQS: " + sqsMessage + 
                        " -> Spring: " + springMessage + " on thread: " + Thread.currentThread().getId());
      
      // Transfer state from SQS message to Spring message
      // if (null != sqsMessage && null != springMessage &&
      //     sqsMessage.getClass().getName().equals("software.amazon.awssdk.services.sqs.model.Message")) {
      //   
      //   ContextStore<Object, State> from =
      //       InstrumentationContext.get(Object.class, State.class);
      //   State state = from.get(sqsMessage);
      //   if (null != state) {
      //     from.put(sqsMessage, null);
      //     // InstrumentationContext.get(Message.class, State.class).put(springMessage, state);
      //     System.out.println("[ToMessaging] Transferred state from SQS message to Spring message on thread: " +
      //                       Thread.currentThread().getId());
      //   } else {
      //     System.out.println("[ToMessaging] No state found in SQS message during conversion on thread: " +
      //                       Thread.currentThread().getId());
      //   }
      // } else {
      //   System.out.println("[ToMessaging] Skipping transfer - not an SQS message or null message on thread: " +
      //                     Thread.currentThread().getId());
      // }
    }
  }
}
