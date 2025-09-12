package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class FallbackCallableInstrumentation extends Resilience4jInstrumentation {
  public FallbackCallableInstrumentation() {
    super("resilience4j-fallback");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.core.CallableUtils";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("recover", "andThen")).and(returns(named(CALLABLE_FQCN))),
        FallbackCallableInstrumentation.class.getName() + "$CallableAdvice");
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(@Advice.Return(readOnly = false) Callable<?> outbound) {
      outbound =
          new WrapperWithContext.CallableWithContext<>(
              outbound, Resilience4jSpanDecorator.DECORATE, null);
    }
  }
}
