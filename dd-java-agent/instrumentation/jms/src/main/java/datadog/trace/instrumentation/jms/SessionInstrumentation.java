package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
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
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  public static final class CreateConsumer {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void bindDestinationName(
        @Advice.This Session session,
        @Advice.Argument(0) Destination destination,
        @Advice.Return MessageConsumer consumer) {
      int acknowledgeMode;
      try {
        acknowledgeMode = session.getAcknowledgeMode();
      } catch (JMSException e) {
        acknowledgeMode = Session.AUTO_ACKNOWLEDGE;
      }
      ContextStore<MessageConsumer, MessageConsumerState> contextStore =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class);
      // avoid doing the same thing more than once when there is delegation to overloads
      if (contextStore.get(consumer) == null) {
        // logic inlined from JMSDecorator to avoid
        // JMSException: A consumer is consuming from the temporary destination
        String resourceName = "Consumed from Destination";
        try {
          // put the common case first
          if (destination instanceof Queue) {
            String queueName = ((Queue) destination).getQueueName();
            if ((destination instanceof TemporaryQueue || queueName.startsWith("$TMP$"))) {
              resourceName = "Consumed from Temporary Queue";
            } else {
              resourceName = "Consumed from Queue " + queueName;
            }
          } else if (destination instanceof Topic) {
            String topicName = ((Topic) destination).getTopicName();
            // this is an odd thing to do so put it second
            if (destination instanceof TemporaryTopic || topicName.startsWith("$TMP$")) {
              resourceName = "Consumed from Temporary Topic";
            } else {
              resourceName = "Consumed from Topic " + topicName;
            }
          }
        } catch (JMSException ignore) {
        }
        // all known MessageConsumer implementations reference
        // the Session so there is no risk of creating a memory
        // leak here. MessageConsumerState could drop the reference
        // to the session to save a bit of space if the implementations
        // were instrumented instead of the interfaces.
        contextStore.put(
            consumer,
            new MessageConsumerState(
                session, acknowledgeMode, UTF8BytesString.create(resourceName)));
      }
    }
  }

  public static final class Commit {
    @Advice.OnMethodEnter
    public static void commit(@Advice.This Session session) {
      SessionState state =
          InstrumentationContext.get(Session.class, SessionState.class).get(session);
      if (null != state) {
        state.onCommit();
      }
    }
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void createSessionState(@Advice.This Session session) {
      InstrumentationContext.get(Session.class, SessionState.class)
          .put(session, new SessionState());
    }
  }
}
