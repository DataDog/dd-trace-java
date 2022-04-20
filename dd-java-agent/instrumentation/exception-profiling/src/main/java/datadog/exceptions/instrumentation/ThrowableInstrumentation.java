package datadog.exceptions.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.none;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Provides instrumentation of {@linkplain Throwable} constructor. <br> */
@AutoService(Instrumenter.class)
public final class ThrowableInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForTypeHierarchy {
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
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    if (hasJfr) {
      return is(Throwable.class);
    }
    return none();
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    if (hasJfr) {
      transformation.applyAdvice(isConstructor(), packageName + ".ThrowableInstanceAdvice");
    }
  }
}
