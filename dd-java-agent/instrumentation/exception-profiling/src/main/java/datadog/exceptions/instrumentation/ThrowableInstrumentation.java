package datadog.exceptions.instrumentation;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Platform;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Provides instrumentation of {@linkplain Throwable} constructor. */
@AutoService(Instrumenter.class)
public final class ThrowableInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForSingleType,
        Instrumenter.WithTypeStructure {

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
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("stackTrace"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), packageName + ".ThrowableInstanceAdvice");
  }
}
