package stackstate.trace.instrumentation.jms1;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static stackstate.trace.instrumentation.jms.util.JmsUtil.toResourceName;

import com.google.auto.service.AutoService;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;
import stackstate.trace.instrumentation.jms.util.MessagePropertyTextMap;

@AutoService(Instrumenter.class)
public final class JMS1MessageConsumerInstrumentation extends Instrumenter.Configurable {
  public static final HelperInjector JMS1_HELPER_INJECTOR =
      new HelperInjector(
          "stackstate.trace.instrumentation.jms.util.JmsUtil",
          "stackstate.trace.instrumentation.jms.util.MessagePropertyTextMap");

  public JMS1MessageConsumerInstrumentation() {
    super("jms", "jms-1");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface()).and(failSafe(hasSuperType(named("javax.jms.MessageConsumer")))),
            not(classLoaderHasClasses("javax.jms.JMSContext", "javax.jms.CompletionListener")))
        .transform(JMS1_HELPER_INJECTOR)
        .transform(STSTransformers.defaultTransformers())
        .transform(
            STSAdvice.create()
                .advice(
                    named("receive").and(takesArguments(0)).and(isPublic()),
                    ConsumerAdvice.class.getName())
                .advice(
                    named("receiveNoWait").and(takesArguments(0)).and(isPublic()),
                    ConsumerAdvice.class.getName()))
        .asDecorator();
  }

  public static class ConsumerAdvice {

    @Advice.OnMethodEnter
    public static long startSpan() {
      return System.currentTimeMillis();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final MessageConsumer consumer,
        @Advice.Enter final long startTime,
        @Advice.Return final Message message,
        @Advice.Thrown final Throwable throwable) {

      final SpanContext extractedContext =
          GlobalTracer.get().extract(Format.Builtin.TEXT_MAP, new MessagePropertyTextMap(message));

      final Scope scope =
          GlobalTracer.get()
              .buildSpan("jms.consume")
              .asChildOf(extractedContext)
              .withTag(STSTags.SERVICE_NAME, "jms")
              .withTag(STSTags.SPAN_TYPE, STSSpanTypes.MESSAGE_CONSUMER)
              .withTag(Tags.COMPONENT.getKey(), "jms1")
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CONSUMER)
              .withTag("span.origin.type", consumer.getClass().getName())
              .withStartTimestamp(TimeUnit.MILLISECONDS.toMicros(startTime))
              .startActive(true);
      final Span span = scope.span();

      if (throwable != null) {
        Tags.ERROR.set(span, Boolean.TRUE);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
      }
      span.setTag(STSTags.RESOURCE_NAME, "Consumed from " + toResourceName(message, null));
      scope.close();
    }
  }
}
