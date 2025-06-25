package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class RetryInstrumentation extends AbstractResilience4jInstrumentation {

  private static final String RETRY_FQCN = "io.github.resilience4j.retry.Retry";

  public RetryInstrumentation() {
    super("resilience4j-retry");
  }

  @Override
  public String instrumentedType() {
    return RETRY_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedSupplier"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(takesArgument(1, named(CHECKED_SUPPLIER_FQCN)))
            .and(returns(named(CHECKED_SUPPLIER_FQCN))),
        RetryInstrumentation.class.getName() + "$CheckedSupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(takesArgument(1, named(SUPPLIER_FQCN)))
            .and(returns(named(SUPPLIER_FQCN))),
        RetryInstrumentation.class.getName() + "$SupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(takesArgument(2, named(SUPPLIER_FQCN)))
            .and(returns(named(SUPPLIER_FQCN))),
        RetryInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Argument(value = 1) CheckedSupplier<?> inbound,
        @Advice.Return(readOnly = false) CheckedSupplier<?> outbound) {
      outbound =
          new ContextHolder.CheckedSupplierWithContext<>(
              outbound, inbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Argument(value = 1) Supplier<?> inbound,
        @Advice.Return(readOnly = false) Supplier<?> outbound) {
      outbound = new ContextHolder.SupplierWithContext(outbound, inbound);
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Argument(value = 2) Supplier<?> inbound,
        @Advice.Return(readOnly = false) Supplier<CompletionStage<?>> outbound) {
      outbound = new ContextHolder.SupplierCompletionStageWithContext(outbound, inbound);
    }
  }
}
