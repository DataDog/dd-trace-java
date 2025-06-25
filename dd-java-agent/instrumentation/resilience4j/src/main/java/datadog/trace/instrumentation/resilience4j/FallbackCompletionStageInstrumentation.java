package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class FallbackCompletionStageInstrumentation extends FallbackAbstractInstrumentation {
  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.core.CompletionStageUtils";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("recover", "andThen"))
            .and(takesArgument(0, named(SUPPLIER_FQCN)))
            .and(returns(named(SUPPLIER_FQCN))),
        FallbackCompletionStageInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Supplier<?> inbound,
        @Advice.Return(readOnly = false) Supplier<CompletionStage<?>> outbound) {
      outbound = new ContextHolder.SupplierCompletionStageWithContext(outbound, inbound);
    }
  }
}
