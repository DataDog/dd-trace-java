package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class RetryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final String RETRY_FQCN = "io.github.resilience4j.retry.Retry";

  public RetryInstrumentation() {
    super("resilience4j", "resilience4j-retry");
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
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(takesArgument(2, named("java.util.function.Supplier"))),
        RetryInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DDContext", packageName + ".RetryWithContext",
    };
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) Retry retry,
        @Advice.Argument(value = 2, readOnly = false) Supplier<CompletionStage<?>> supplier) {
      DDContext ddContext = new DDContext();
      final Supplier<CompletionStage<?>> delegate = supplier;
      retry = new RetryWithContext(retry, ddContext);
      supplier = DDContext.wrap(delegate, ddContext);
    }
  }
}
