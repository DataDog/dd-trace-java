package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.reactivestreams.HandoffContext;
import io.github.resilience4j.retry.Retry;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;

public class RetryOperatorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.retry.RetryOperator";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("apply"))
            .and(takesArgument(0, named("org.reactivestreams.Publisher"))),
        RetryOperatorInstrumentation.class.getName() + "$ApplyAdvice");
  }

  public static class ApplyAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Return(readOnly = false) Publisher<?> result,
        @Advice.FieldValue(value = "retry") Retry retry) {

      result =
          ReactorHelper.wrapPublisher(
              result,
              RetryDecorator.DECORATE,
              retry,
              ReactorHelper.putInto(
                  InstrumentationContext.get(Publisher.class, HandoffContext.class)));
    }
  }
}
