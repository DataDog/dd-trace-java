package datadog.trace.instrumentation.resilience4j;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class Resilience4jInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public static final String CHECKED_SUPPLIER_FQCN =
      "io.github.resilience4j.core.functions.CheckedSupplier";
  public static final String CHECKED_RUNNABLE_FQCN =
      "io.github.resilience4j.core.functions.CheckedRunnable";
  public static final String CHECKED_FUNCTION_FQCN =
      "io.github.resilience4j.core.functions.CheckedFunction";
  public static final String CHECKED_CONSUMER_FQCN =
      "io.github.resilience4j.core.functions.CheckedConsumer";
  public static final String SUPPLIER_FQCN = Supplier.class.getName();
  public static final String FUNCTION_FQCN = Function.class.getName();
  public static final String CONSUMER_FQCN = Consumer.class.getName();
  public static final String CALLABLE_FQCN = Callable.class.getName();
  public static final String RUNNABLE_FQCN = Runnable.class.getName();

  public Resilience4jInstrumentation(String... additionalNames) {
    super("resilience4j", additionalNames);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WrapperWithContext",
      packageName + ".WrapperWithContext$CallableWithContext",
      packageName + ".WrapperWithContext$CheckedRunnableWithContext",
      packageName + ".WrapperWithContext$RunnableWithContext",
      packageName + ".WrapperWithContext$CheckedFunctionWithContext",
      packageName + ".WrapperWithContext$ConsumerWithContext",
      packageName + ".WrapperWithContext$CheckedSupplierWithContext",
      packageName + ".WrapperWithContext$CheckedConsumerWithContext",
      packageName + ".WrapperWithContext$FunctionWithContext",
      packageName + ".WrapperWithContext$SupplierOfCompletionStageWithContext",
      packageName + ".WrapperWithContext$SupplierWithContext",
      packageName + ".Resilience4jSpanDecorator",
      packageName + ".Resilience4jSpan",
      packageName + ".CircuitBreakerDecorator",
      packageName + ".RetryDecorator",
    };
  }
}
