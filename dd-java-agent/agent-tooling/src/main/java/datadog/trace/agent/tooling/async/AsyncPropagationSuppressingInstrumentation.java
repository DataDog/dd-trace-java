package datadog.trace.agent.tooling.async;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Suppresses executor context capture around library-internal background work. */
public abstract class AsyncPropagationSuppressingInstrumentation
    extends InstrumenterModule.ContextTracking implements Instrumenter.HasMethodAdvice {

  protected AsyncPropagationSuppressingInstrumentation() {
    // These protections are needed even when the library's tracing instrumentation is disabled.
    super("java_concurrent");
  }

  /** Selects methods within the types declared by the instrumentation's matching interfaces. */
  protected abstract ElementMatcher<? super MethodDescription> suppressedMethods();

  @Override
  public final void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(suppressedMethods(), SuppressAsyncPropagationAdvice.class.getName());
  }

  @Override
  public final String muzzleDirective() {
    // Advice only references bootstrap APIs; library version checks belong to tracing advice.
    return "async-propagation-suppression";
  }
}
