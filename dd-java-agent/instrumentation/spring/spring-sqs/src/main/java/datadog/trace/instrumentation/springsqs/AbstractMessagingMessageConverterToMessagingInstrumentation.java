package datadog.trace.instrumentation.springsqs;

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
import java.util.HashMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.messaging.Message;

@AutoService(InstrumenterModule.class)
public class AbstractMessagingMessageConverterToMessagingInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AbstractMessagingMessageConverterToMessagingInstrumentation() {
    super("spring-sqs");
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
    Map<String, String> contextStore = new HashMap<>(2);
    contextStore.put("software.amazon.awssdk.services.sqs.model.Message", State.class.getName());
    contextStore.put("org.springframework.messaging.Message", State.class.getName());
    return contextStore;
  }

  public static class ToMessagingMessageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) Object sqsMessage,
        @Advice.Return Message springMessage) {
      // Transfer state from SQS message to Spring message
      if (null != sqsMessage && null != springMessage &&
          sqsMessage.getClass().getName().equals("software.amazon.awssdk.services.sqs.model.Message")) {
        
        ContextStore<software.amazon.awssdk.services.sqs.model.Message, State> from =
            InstrumentationContext.get(software.amazon.awssdk.services.sqs.model.Message.class, State.class);
        State state = from.get((software.amazon.awssdk.services.sqs.model.Message) sqsMessage);
        if (null != state) {
          from.put((software.amazon.awssdk.services.sqs.model.Message) sqsMessage, null);
          // Transfer state from SQS Message to Spring Message
          ContextStore<Message, State> to = 
              InstrumentationContext.get(Message.class, State.class);
          to.put(springMessage, state);
          System.out.println("[ToMessaging] Transferred state from SQS message to Spring message on thread: " +
                            Thread.currentThread().getId());
        } else {
          System.out.println("[ToMessaging] No state found in SQS message during conversion on thread: " +
                            Thread.currentThread().getId());
        }
      } else {
        System.out.println("[ToMessaging] Skipping transfer - not an SQS message or null message on thread: " +
                          Thread.currentThread().getId());
      }
    }
  }
}
