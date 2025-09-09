package datadog.trace.instrumentation.springjms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import java.util.Map;
import javax.jms.MessageConsumer;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class AbstractPollingMessageListenerContainerInstrumentation
    extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AbstractPollingMessageListenerContainerInstrumentation() {
    super("spring-jms", "jms");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.jms.listener.AbstractPollingMessageListenerContainer";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.jms.MessageConsumer", MessageConsumerState.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("receiveAndExecute").and(takesArgument(2, named("javax.jms.MessageConsumer"))),
        getClass().getName() + "$CompleteMessageSpanAfterExecute");
  }

  public static class CompleteMessageSpanAfterExecute {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterExecute(@Advice.Argument(2) final MessageConsumer consumer) {
      if (null == consumer) {
        return; // temporary consumer was created+closed during the call, need for extra clean-up
      }

      // complete the last polled message span now that we know its execution phase is complete
      // this results in more accurate durations than if we relied on the iteration span cleaner
      // (uses same approach as the 'beforeReceive' advice in JMSMessageConsumerInstrumentation)
      MessageConsumerState consumerState =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
              .get(consumer);
      if (null != consumerState) {
        boolean finishSpan = consumerState.getSessionState().isAutoAcknowledge();
        closePrevious(finishSpan);
        if (finishSpan) {
          consumerState.finishTimeInQueueSpan(false);
        }
      }
    }
  }
}
