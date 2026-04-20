package datadog.exceptions.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;

/** Provides instrumentation of {@linkplain Exception} and {@linkplain Error} constructors. */
@AutoService(InstrumenterModule.class)
public final class ThrowableInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public ThrowableInstrumentation() {
    super("throwables");
  }

  @Override
  public boolean isEnabled() {
    return Platform.hasJfr() && super.isEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".ThrowableInstanceAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "java.lang.Exception", "java.lang.Error", "kotlin.Exception", "kotlin.Error"
    };
  }
}
