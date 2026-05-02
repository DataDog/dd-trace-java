package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.function.Function;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;

public class FallbackOperatorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.ReactorOperatorFallbackDecorator";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("decorate"))
            .and(
                takesArgument(0, named("java.util.function.UnaryOperator"))
                    .and(returns(named("java.util.function.Function")))),
        FallbackOperatorInstrumentation.class.getName() + "$DecorateAdvice");
  }

  public static class DecorateAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Return(readOnly = false) Function<Publisher<?>, Publisher<?>> result) {

      result =
          ReactorHelper.wrapFunction(
              result, InstrumentationContext.get(Publisher.class, Context.class)::putIfAbsent);
    }

    // 2.0.0+
    public static void muzzleCheck(CheckedSupplier<?> cs) throws Throwable {
      cs.get();
    }
  }
}
