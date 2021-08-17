package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import java.util.HashMap;
import java.util.Map;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SessionInstrumentation extends Instrumenter.Tracing {
  public SessionInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return hasInterface(named("javax.jms.Session"));
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("javax.jms.MessageConsumer", MessageConsumerState.class.getName());
    contextStore.put("javax.jms.Session", SessionState.class.getName());
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("createConsumer"))
            .and(isPublic())
            .and(takesArgument(0, named("javax.jms.Destination"))),
        getClass().getName() + "$CreateConsumer");
    transformation.applyAdvice(
        namedOneOf("commit", "close", "rollback").and(takesNoArguments()),
        getClass().getName() + "$Commit");
  }

  public static final class CreateConsumer {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void bindDestinationName(
        @Advice.This Session session,
        @Advice.Argument(0) Destination destination,
        @Advice.Return MessageConsumer consumer) {
      ContextStore<MessageConsumer, MessageConsumerState> consumerStateStore =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class);
      // avoid doing the same thing more than once when there is delegation to overloads
      if (consumerStateStore.get(consumer) == null) {
        int ackMode;
        try {
          ackMode = session.getAcknowledgeMode();
        } catch (JMSException e) {
          ackMode = Session.AUTO_ACKNOWLEDGE;
        }
        // logic inlined from JMSDecorator to avoid
        // JMSException: A consumer is consuming from the temporary destination
        String resourceName = "Consumed from Destination";
        String destinationName = "";
        try {
          // put the common case first
          if (destination instanceof Queue) {
            destinationName = ((Queue) destination).getQueueName();
            if ((destination instanceof TemporaryQueue || destinationName.startsWith("$TMP$"))) {
              resourceName = "Consumed from Temporary Queue";
            } else {
              resourceName = "Consumed from Queue " + destinationName;
            }
          } else if (destination instanceof Topic) {
            destinationName = ((Topic) destination).getTopicName();
            // this is an odd thing to do so put it second
            if (destination instanceof TemporaryTopic || destinationName.startsWith("$TMP$")) {
              resourceName = "Consumed from Temporary Topic";
            } else {
              resourceName = "Consumed from Topic " + destinationName;
            }
          }
        } catch (JMSException ignore) {
        }
        ContextStore<Session, SessionState> sessionStateStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        SessionState sessionState = sessionStateStore.get(session);
        if (null == sessionState) {
          sessionState = sessionStateStore.putIfAbsent(session, new SessionState(ackMode));
        }
        // all known MessageConsumer implementations reference
        // the Session so there is no risk of creating a memory
        // leak here. MessageConsumerState could drop the reference
        // to the session to save a bit of space if the implementations
        // were instrumented instead of the interfaces.
        consumerStateStore.put(
            consumer,
            new MessageConsumerState(
                sessionState, UTF8BytesString.create(resourceName), destinationName));
      }
    }
  }

  public static final class Commit {
    @Advice.OnMethodEnter
    public static void commit(@Advice.This Session session) {
      SessionState sessionState =
          InstrumentationContext.get(Session.class, SessionState.class).get(session);
      if (null != sessionState) {
        sessionState.onCommit();
      }
    }
  }
}
