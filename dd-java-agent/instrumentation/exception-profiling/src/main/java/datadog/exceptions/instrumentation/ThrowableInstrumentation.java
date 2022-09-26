package datadog.exceptions.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;

/** Provides instrumentation of {@linkplain Throwable} constructor. */
@AutoService(Instrumenter.class)
public final class ThrowableInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public ThrowableInstrumentation() {
    super("throwables");
  }

  @Override
  public boolean isEnabled() {
    return Platform.hasJfr() && super.isEnabled();
  }

  @Override
  public String instrumentedType() {
    return "java.lang.Throwable";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), packageName + ".ThrowableInstanceAdvice");
  }
}
