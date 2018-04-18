package stackstate.trace.instrumentation.jms1;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static stackstate.trace.instrumentation.jms.util.JmsUtil.toResourceName;

import com.google.auto.service.AutoService;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.instrumentation.jms.util.MessagePropertyTextMap;

@AutoService(Instrumenter.class)
public final class JMS1MessageProducerInstrumentation extends Instrumenter.Configurable {

  public JMS1MessageProducerInstrumentation() {
    super("jms", "jms-1");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface()).and(failSafe(hasSuperType(named("javax.jms.MessageProducer")))),
            not(classLoaderHasClasses("javax.jms.JMSContext", "javax.jms.CompletionListener")))
        .transform(JMS1MessageConsumerInstrumentation.JMS1_HELPER_INJECTOR)
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    named("send").and(takesArgument(0, named("javax.jms.Message"))).and(isPublic()),
                    ProducerAdvice.class.getName())
                .advice(
                    named("send")
                        .and(takesArgument(0, named("javax.jms.Destination")))
                        .and(takesArgument(1, named("javax.jms.Message")))
                        .and(isPublic()),
                    ProducerWithDestinationAdvice.class.getName()))
        .asDecorator();
  }

  public static class ProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(
        @Advice.Argument(0) final Message message, @Advice.This final MessageProducer producer) {
      Destination defaultDestination;
      try {
        defaultDestination = producer.getDestination();
      } catch (final JMSException e) {
        defaultDestination = null;
      }
      final Scope scope =
          GlobalTracer.get()
              .buildSpan("jms.produce")
              .withTag(DDTags.SERVICE_NAME, "jms")
              .withTag(
                  DDTags.RESOURCE_NAME,
                  "Produced for " + toResourceName(message, defaultDestination))
              .withTag(Tags.COMPONENT.getKey(), "jms1")
              .withTag(DDTags.SPAN_TYPE, DDSpanTypes.MESSAGE_PRODUCER)
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_PRODUCER)
              .withTag("span.origin.type", producer.getClass().getName())
              .startActive(true);

      GlobalTracer.get()
          .inject(
              scope.span().context(), Format.Builtin.TEXT_MAP, new MessagePropertyTextMap(message));

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {

      if (scope != null) {
        if (throwable != null) {
          final Span span = scope.span();
          Tags.ERROR.set(span, Boolean.TRUE);
          span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        }
        scope.close();
      }
    }
  }

  public static class ProducerWithDestinationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(
        @Advice.Argument(0) final Destination destination,
        @Advice.Argument(1) final Message message,
        @Advice.This final MessageProducer producer) {
      final Scope scope =
          GlobalTracer.get()
              .buildSpan("jms.produce")
              .withTag(DDTags.SERVICE_NAME, "jms")
              .withTag(DDTags.SPAN_TYPE, DDSpanTypes.MESSAGE_PRODUCER)
              .withTag(DDTags.RESOURCE_NAME, "Produced for " + toResourceName(message, destination))
              .withTag(Tags.COMPONENT.getKey(), "jms1")
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_PRODUCER)
              .withTag("span.origin.type", producer.getClass().getName())
              .startActive(true);

      GlobalTracer.get()
          .inject(
              scope.span().context(), Format.Builtin.TEXT_MAP, new MessagePropertyTextMap(message));

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {

      if (scope != null) {
        if (throwable != null) {
          final Span span = scope.span();
          Tags.ERROR.set(span, Boolean.TRUE);
          span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        }
        scope.close();
      }
    }
  }
}
