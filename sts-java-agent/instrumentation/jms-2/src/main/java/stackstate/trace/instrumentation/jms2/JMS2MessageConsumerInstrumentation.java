package stackstate.trace.instrumentation.jms2;

import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static stackstate.trace.instrumentation.jms.util.JmsUtil.toResourceName;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;
import stackstate.trace.instrumentation.jms.util.MessagePropertyTextMap;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JMS2MessageConsumerInstrumentation extends Instrumenter.Default {
  public static final String[] JMS2_HELPER_CLASS_NAMES =
      new String[] {
        "stackstate.trace.instrumentation.jms.util.JmsUtil",
        "stackstate.trace.instrumentation.jms.util.MessagePropertyTextMap"
      };

  public JMS2MessageConsumerInstrumentation() {
    super("jms", "jms-2");
  }

  @Override
  public ElementMatcher typeMatcher() {
    return not(isInterface()).and(failSafe(hasSuperType(named("javax.jms.MessageConsumer"))));
  }

  @Override
  public ElementMatcher<? super ClassLoader> classLoaderMatcher() {
    return classLoaderHasClasses("javax.jms.JMSContext", "javax.jms.CompletionListener");
  }

  @Override
  public String[] helperClassNames() {
    return JMS2_HELPER_CLASS_NAMES;
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        named("receive").and(takesArguments(0).or(takesArguments(1))).and(isPublic()),
        ConsumerAdvice.class.getName());
    transformers.put(
        named("receiveNoWait").and(takesArguments(0)).and(isPublic()),
        ConsumerAdvice.class.getName());
    return transformers;
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
        @Advice.Origin final Method method,
        @Advice.Return final Message message,
        @Advice.Thrown final Throwable throwable) {
      Tracer.SpanBuilder spanBuilder =
          GlobalTracer.get()
              .buildSpan("jms.consume")
              .withTag(STSTags.SERVICE_NAME, "jms")
              .withTag(STSTags.SPAN_TYPE, STSSpanTypes.MESSAGE_CONSUMER)
              .withTag(Tags.COMPONENT.getKey(), "jms2")
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CONSUMER)
              .withTag("span.origin.type", consumer.getClass().getName())
              .withStartTimestamp(TimeUnit.MILLISECONDS.toMicros(startTime));

      if (message == null) {
        spanBuilder = spanBuilder.withTag(STSTags.RESOURCE_NAME, "JMS " + method.getName());
      } else {
        spanBuilder =
            spanBuilder.withTag(
                STSTags.RESOURCE_NAME, "Consumed from " + toResourceName(message, null));

        final SpanContext extractedContext =
            GlobalTracer.get()
                .extract(Format.Builtin.TEXT_MAP, new MessagePropertyTextMap(message));
        if (extractedContext != null) {
          spanBuilder = spanBuilder.asChildOf(extractedContext);
        }
      }

      final Scope scope = spanBuilder.startActive(true);
      final Span span = scope.span();

      if (throwable != null) {
        Tags.ERROR.set(span, Boolean.TRUE);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
      }

      scope.close();
    }
  }
}
