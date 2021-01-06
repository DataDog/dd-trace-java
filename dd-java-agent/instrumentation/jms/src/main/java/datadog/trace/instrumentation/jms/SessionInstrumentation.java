package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.Topic;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
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
    return singletonMap("javax.jms.MessageConsumer", UTF8BytesString.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("createConsumer"))
            .and(isPublic())
            .and(takesArgument(0, named("javax.jms.Destination"))),
        getClass().getName() + "$CreateConsumer");
  }

  public static final class CreateConsumer {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void bindDestinationName(
        @Advice.Argument(0) Destination destination, @Advice.Return MessageConsumer consumer) {
      ContextStore<MessageConsumer, UTF8BytesString> contextStore =
          InstrumentationContext.get(MessageConsumer.class, UTF8BytesString.class);
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
        contextStore.put(consumer, UTF8BytesString.create(resourceName));
      }
    }
  }
}
