package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_DELIVER;
import static datadog.trace.instrumentation.jms.JMSDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.jms.MessageExtractAdapter.GETTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import java.util.HashMap;
import java.util.Map;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JMSMessageConsumerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public JMSMessageConsumerInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.jms.MessageConsumer";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageExtractAdapter$1",
      packageName + ".DatadogMessageListener"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("javax.jms.MessageConsumer", MessageConsumerState.class.getName());
    contextStore.put("javax.jms.Message", SessionState.class.getName());
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("receive").and(takesArguments(0).or(takesArguments(1))).and(isPublic()),
        JMSMessageConsumerInstrumentation.class.getName() + "$ConsumerAdvice");
    transformation.applyAdvice(
        named("receiveNoWait").and(takesArguments(0)).and(isPublic()),
        JMSMessageConsumerInstrumentation.class.getName() + "$ConsumerAdvice");
    transformation.applyAdvice(
        named("close").and(takesArguments(0)).and(isPublic()),
        JMSMessageConsumerInstrumentation.class.getName() + "$Close");
    transformation.applyAdvice(
        isMethod()
            .and(named("setMessageListener"))
            .and(takesArgument(0, hasInterface(named("javax.jms.MessageListener")))),
        getClass().getName() + "$DecorateMessageListener");
  }

  public static class ConsumerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static MessageConsumerState beforeReceive(@Advice.This final MessageConsumer consumer) {
      MessageConsumerState consumerState =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
              .get(consumer);

      // ignore consumers who aren't bound to a tracked session via consumerState
      if (null == consumerState) {
        return null;
      }

      boolean finishSpan = consumerState.getSessionState().isAutoAcknowledge();
      closePrevious(finishSpan);
      if (finishSpan) {
        consumerState.finishTimeInQueueSpan(false);
      }

      // don't create spans for nested receive calls, even if different consumers are involved
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MessageConsumer.class);
      if (callDepth > 0) {
        return null;
      }

      return consumerState;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterReceive(
        @Advice.Enter final MessageConsumerState consumerState,
        @Advice.This final MessageConsumer consumer,
        @Advice.Return final Message message,
        @Advice.Thrown final Throwable throwable) {

      if (consumerState == null) {
        // either we're not tracking the consumer or this is a nested receive
        return;
      }

      // outermost receive call - make sure we reset call-depth before returning
      CallDepthThreadLocalMap.reset(MessageConsumer.class);

      if (message == null) {
        // don't create spans (traces) for each poll if the queue is empty
        return;
      }

      AgentSpan span;
      AgentSpan.Context propagatedContext = null;
      if (!consumerState.isPropagationDisabled()) {
        propagatedContext = propagate().extract(message, GETTER);
      }
      long startMillis = GETTER.extractTimeInQueueStart(message);
      if (startMillis == 0 || !TIME_IN_QUEUE_ENABLED) {
        span = startSpan(JMS_CONSUME, propagatedContext);
      } else {
        long batchId = GETTER.extractMessageBatchId(message);
        AgentSpan timeInQueue = consumerState.getTimeInQueueSpan(batchId);
        if (null == timeInQueue) {
          timeInQueue =
              startSpan(JMS_DELIVER, propagatedContext, MILLISECONDS.toMicros(startMillis));
          BROKER_DECORATE.afterStart(timeInQueue);
          BROKER_DECORATE.onTimeInQueue(
              timeInQueue,
              consumerState.getBrokerResourceName(),
              consumerState.getBrokerServiceName());
          consumerState.setTimeInQueueSpan(batchId, timeInQueue);
        }
        span = startSpan(JMS_CONSUME, timeInQueue.context());
      }

      CONSUMER_DECORATE.afterStart(span);
      CONSUMER_DECORATE.onConsume(span, message, consumerState.getConsumerResourceName());
      CONSUMER_DECORATE.onError(span, throwable);

      activateNext(span); // scope is left open until next message or it times out

      SessionState sessionState = consumerState.getSessionState();
      if (sessionState.isClientAcknowledge()) {
        // consumed spans will be finished by a call to Message.acknowledge
        sessionState.finishOnAcknowledge(span);
        InstrumentationContext.get(Message.class, SessionState.class).put(message, sessionState);
      } else if (sessionState.isTransactedSession()) {
        // span will be finished by Session.commit/rollback/close
        sessionState.finishOnCommit(span);
      }
      // for AUTO_ACKNOWLEDGE, span is not finished until next call to receive, or close
    }
  }

  public static class Close {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeClose(@Advice.This final MessageConsumer consumer) {
      MessageConsumerState consumerState =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
              .get(consumer);
      if (null != consumerState) {
        boolean finishSpan = consumerState.getSessionState().isAutoAcknowledge();
        closePrevious(finishSpan);
        if (finishSpan) {
          consumerState.finishTimeInQueueSpan(true);
        }
      }
    }
  }

  public static class DecorateMessageListener {
    @Advice.OnMethodEnter
    public static void setMessageListener(
        @Advice.This MessageConsumer messageConsumer,
        @Advice.Argument(value = 0, readOnly = false) MessageListener listener) {
      if (null != listener && !(listener instanceof DatadogMessageListener)) {
        MessageConsumerState consumerState =
            InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
                .get(messageConsumer);
        if (null != consumerState) {
          listener =
              new DatadogMessageListener(
                  InstrumentationContext.get(Message.class, SessionState.class),
                  consumerState,
                  listener);
        }
      }
    }
  }
}
