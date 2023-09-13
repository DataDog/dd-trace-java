package datadog.trace.instrumentation.jakarta.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.JMS_LEGACY_TRACING;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.jakarta.jms.JMSDecorator.TIME_IN_QUEUE_ENABLED;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.MessageProducerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import jakarta.jms.Destination;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SessionInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public SessionInstrumentation() {
    super("jakarta-jms");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.jms.Session";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JMSDecorator"};
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("jakarta.jms.MessageConsumer", MessageConsumerState.class.getName());
    contextStore.put("jakarta.jms.MessageProducer", MessageProducerState.class.getName());
    contextStore.put("jakarta.jms.Session", SessionState.class.getName());
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("createProducer"))
            .and(isPublic())
            .and(takesArgument(0, named("jakarta.jms.Destination"))),
        getClass().getName() + "$CreateProducer");
    transformation.applyAdvice(
        isMethod()
            .and(named("createSender"))
            .and(isPublic())
            .and(takesArgument(0, named("jakarta.jms.Queue"))),
        getClass().getName() + "$CreateProducer");
    transformation.applyAdvice(
        isMethod()
            .and(named("createPublisher"))
            .and(isPublic())
            .and(takesArgument(0, named("jakarta.jms.Topic"))),
        getClass().getName() + "$CreateProducer");

    transformation.applyAdvice(
        isMethod()
            .and(named("createConsumer"))
            .and(isPublic())
            .and(takesArgument(0, named("jakarta.jms.Destination"))),
        getClass().getName() + "$CreateConsumer");
    transformation.applyAdvice(
        isMethod()
            .and(named("createReceiver"))
            .and(isPublic())
            .and(takesArgument(0, named("jakarta.jms.Queue"))),
        getClass().getName() + "$CreateConsumer");
    transformation.applyAdvice(
        isMethod()
            .and(namedOneOf("createSubscriber", "createDurableSubscriber"))
            .and(isPublic())
            .and(takesArgument(0, named("jakarta.jms.Topic"))),
        getClass().getName() + "$CreateConsumer");

    transformation.applyAdvice(
        namedOneOf("recover").and(takesNoArguments()), getClass().getName() + "$Recover");
    transformation.applyAdvice(
        namedOneOf("commit", "rollback").and(takesNoArguments()), getClass().getName() + "$Commit");
    transformation.applyAdvice(
        named("close").and(takesNoArguments()), getClass().getName() + "$Close");
  }

  public static final class CreateProducer {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void bindProducerState(
        @Advice.This Session session,
        @Advice.Argument(0) Destination destination,
        @Advice.Return MessageProducer producer) {

      ContextStore<MessageProducer, MessageProducerState> producerStateStore =
          InstrumentationContext.get(MessageProducer.class, MessageProducerState.class);

      // avoid doing the same thing more than once when there is delegation to overloads
      if (producerStateStore.get(producer) == null) {
        ContextStore<Session, SessionState> sessionStateStore =
            InstrumentationContext.get(Session.class, SessionState.class);

        SessionState sessionState = sessionStateStore.get(session);
        if (null == sessionState) {
          int ackMode;
          try {
            ackMode = session.getAcknowledgeMode();
          } catch (Exception ignored) {
            ackMode = Session.AUTO_ACKNOWLEDGE;
          }
          sessionState =
              sessionStateStore.putIfAbsent(
                  session, new SessionState(ackMode, TIME_IN_QUEUE_ENABLED));
        }

        boolean isQueue = PRODUCER_DECORATE.isQueue(destination);
        String destinationName = PRODUCER_DECORATE.getDestinationName(destination);
        CharSequence resourceName = PRODUCER_DECORATE.toResourceName(destinationName, isQueue);

        boolean propagationDisabled =
            Config.get().isJmsPropagationDisabledForDestination(destinationName);

        producerStateStore.put(
            producer, new MessageProducerState(sessionState, resourceName, propagationDisabled));
      }
    }
  }

  public static final class CreateConsumer {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void bindConsumerState(
        @Advice.This Session session,
        @Advice.Argument(0) Destination destination,
        @Advice.Return MessageConsumer consumer) {

      ContextStore<MessageConsumer, MessageConsumerState> consumerStateStore =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class);

      // avoid doing the same thing more than once when there is delegation to overloads
      if (consumerStateStore.get(consumer) == null) {
        ContextStore<Session, SessionState> sessionStateStore =
            InstrumentationContext.get(Session.class, SessionState.class);

        SessionState sessionState = sessionStateStore.get(session);
        if (null == sessionState) {
          int ackMode;
          try {
            ackMode = session.getAcknowledgeMode();
          } catch (Exception ignored) {
            ackMode = Session.AUTO_ACKNOWLEDGE;
          }
          sessionState =
              sessionStateStore.putIfAbsent(
                  session, new SessionState(ackMode, TIME_IN_QUEUE_ENABLED));
        }

        boolean isQueue = CONSUMER_DECORATE.isQueue(destination);
        String destinationName = CONSUMER_DECORATE.getDestinationName(destination);
        CharSequence brokerResourceName =
            JMS_LEGACY_TRACING ? "jms" : BROKER_DECORATE.toResourceName(destinationName, isQueue);
        CharSequence consumerResourceName =
            CONSUMER_DECORATE.toResourceName(destinationName, isQueue);

        boolean propagationDisabled =
            Config.get().isJmsPropagationDisabledForDestination(destinationName);

        consumerStateStore.put(
            consumer,
            new MessageConsumerState(
                sessionState, brokerResourceName, consumerResourceName, propagationDisabled));
      }
    }
  }

  public static final class Recover {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void recover(@Advice.This Session session) {
      SessionState sessionState =
          InstrumentationContext.get(Session.class, SessionState.class).get(session);
      if (null != sessionState && sessionState.isClientAcknowledge()) {
        sessionState.onAcknowledgeOrRecover();
      }
    }
  }

  public static final class Commit {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void commit(@Advice.This Session session) {
      SessionState sessionState =
          InstrumentationContext.get(Session.class, SessionState.class).get(session);
      if (null != sessionState && sessionState.isTransactedSession()) {
        sessionState.onCommitOrRollback();
      }
    }
  }

  public static final class Close {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This Session session) {
      SessionState sessionState =
          InstrumentationContext.get(Session.class, SessionState.class).get(session);
      if (null != sessionState) {
        sessionState.onClose();
      }
    }
  }
}
