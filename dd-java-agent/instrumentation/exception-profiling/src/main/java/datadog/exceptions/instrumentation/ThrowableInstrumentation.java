package datadog.exceptions.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

/** Provides instrumentation of {@linkplain Throwable} constructor. */
@AutoService(Instrumenter.class)
public final class ThrowableInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForSingleType {
  private final boolean hasJfr;

  public ThrowableInstrumentation() {
    super("throwables");
    /* Check only for the open-sources JFR implementation.
     * If it is ever needed to support also the closed sourced JDK 8 version the check should be
     * enhanced.
     * Need this custom check because ClassLoaderMatchers.hasClassesNamed() does not support bootstrap class loader yet.
     * Note: the downside of this is that we load some JFR classes at startup.
     * Note2: we cannot check that we can load ExceptionSampleEvent because it is not available on the class path yet.
     */
    hasJfr = ClassLoader.getSystemClassLoader().getResource("jdk/jfr/Event.class") != null;
  }

  @Override
  public boolean isEnabled() {
    return hasJfr && super.isEnabled();
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
