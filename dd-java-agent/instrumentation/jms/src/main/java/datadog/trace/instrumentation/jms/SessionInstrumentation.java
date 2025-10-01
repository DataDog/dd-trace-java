package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.jms.JMSDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_LEGACY_TRACING;
import static datadog.trace.instrumentation.jms.JMSDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.TIME_IN_QUEUE_ENABLED;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.MessageProducerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class SessionInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public SessionInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String hierarchyMarkerType() {
    return namespace + ".jms.Session";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("createProducer"))
            .and(isPublic())
            .and(takesArgument(0, named(namespace + ".jms.Destination"))),
        getClass().getName() + "$CreateProducer");
    transformer.applyAdvice(
        isMethod()
            .and(named("createSender"))
            .and(isPublic())
            .and(takesArgument(0, named(namespace + ".jms.Queue"))),
        getClass().getName() + "$CreateProducer");
    transformer.applyAdvice(
        isMethod()
            .and(named("createPublisher"))
            .and(isPublic())
            .and(takesArgument(0, named(namespace + ".jms.Topic"))),
        getClass().getName() + "$CreateProducer");

    transformer.applyAdvice(
        isMethod()
            .and(named("createConsumer"))
            .and(isPublic())
            .and(takesArgument(0, named(namespace + ".jms.Destination"))),
        getClass().getName() + "$CreateConsumer");
    transformer.applyAdvice(
        isMethod()
            .and(named("createReceiver"))
            .and(isPublic())
            .and(takesArgument(0, named(namespace + ".jms.Queue"))),
        getClass().getName() + "$CreateConsumer");
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("createSubscriber", "createDurableSubscriber"))
            .and(isPublic())
            .and(takesArgument(0, named(namespace + ".jms.Topic"))),
        getClass().getName() + "$CreateConsumer");

    transformer.applyAdvice(
        namedOneOf("recover").and(takesNoArguments()), getClass().getName() + "$Recover");
    transformer.applyAdvice(
        namedOneOf("commit", "rollback").and(takesNoArguments()), getClass().getName() + "$Commit");
    transformer.applyAdvice(
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
                sessionState,
                brokerResourceName,
                destinationName,
                consumerResourceName,
                propagationDisabled));
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
