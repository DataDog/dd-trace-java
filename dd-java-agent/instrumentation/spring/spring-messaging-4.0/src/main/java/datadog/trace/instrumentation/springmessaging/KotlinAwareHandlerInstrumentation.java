package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;

/**
 * Instruments {@code KotlinAwareInvocableHandlerMethod.doInvoke()} to attach the current {@link
 * Context} to the returned {@link Publisher} so that the reactive-streams instrumentation activates
 * it during subscription.
 *
 * <p>When a Spring Kafka listener is a Kotlin {@code suspend fun}, {@code
 * KotlinAwareInvocableHandlerMethod.doInvoke()} returns a cold {@code Mono} immediately, before the
 * listener body runs. By the time the {@code Mono} is subscribed (and the underlying {@code
 * AbstractCoroutine} is constructed), the {@code spring.consume} scope opened by {@link
 * SpringMessageHandlerInstrumentation} has already been closed. This advice captures {@link
 * Context#current()} at {@code doInvoke()} exit — while {@code spring.consume} is still active —
 * and stores it on the Publisher. The reactive-streams {@code PublisherInstrumentation} then
 * retrieves and activates it during subscription so that {@code DatadogThreadContextElement} picks
 * up the correct parent context when the underlying {@code AbstractCoroutine} is constructed.
 */
@AutoService(InstrumenterModule.class)
public class KotlinAwareHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KotlinAwareHandlerInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.reactivestreams.Publisher", Context.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Collections.singletonList(new KotlinAwareHandlerInstrumentation());
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.kafka.listener.adapter.KotlinAwareInvocableHandlerMethod";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("doInvoke")),
        KotlinAwareHandlerInstrumentation.class.getName() + "$DoInvokeAdvice");
  }

  public static class DoInvokeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return Object result) {
      if (result instanceof Publisher) {
        InstrumentationContext.get(Publisher.class, Context.class)
            .put((Publisher<?>) result, Context.current());
      }
    }
  }
}
