package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public class FallbackCompletionStageInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.decorators.Decorators$DecorateCompletionStage";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("withFallback")),
        FallbackCompletionStageInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.FieldValue(value = "stageSupplier", readOnly = false)
            Supplier<CompletionStage<?>> stageSupplier) {
      stageSupplier =
          new WrapperWithContext.SupplierOfCompletionStageWithContext<>(
              stageSupplier, Resilience4jSpanDecorator.DECORATE, null);
    }

    // 2.0.0+
    public static void muzzleCheck(CheckedSupplier<?> cs) throws Throwable {
      cs.get();
    }
  }
}
