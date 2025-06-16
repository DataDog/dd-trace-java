package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.retry.Retry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class RetryInstrumentation extends Resilience4jInstrumentation {

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
    // TODO add synchronous decorator instrumentations
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(takesArgument(2, named("java.util.function.Supplier"))),
        RetryInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  public String[] muzzleIgnoredClassNames() {
    ArrayList<String> ignored = new ArrayList<>(Arrays.asList(helperClassNames()));
    // Prevent a LinkageError caused by a reference to the instrumented interface by excluding these
    // from being loaded by the muzzle check.
    ignored.add(packageName + ".RetryWrapper");
    ignored.add(packageName + ".RetryAsyncContextWrapper");
    return ignored.toArray(new String[0]);
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) Retry retry,
        @Advice.Argument(value = 2, readOnly = false) Supplier<CompletionStage<?>> supplier) {
      DDContext ddContext = new DDContext();
      final Supplier<CompletionStage<?>> delegate = supplier;
      retry = new RetryWrapper(retry, ddContext);
      supplier = DDContext.wrap(delegate, ddContext);
    }
  }
}
