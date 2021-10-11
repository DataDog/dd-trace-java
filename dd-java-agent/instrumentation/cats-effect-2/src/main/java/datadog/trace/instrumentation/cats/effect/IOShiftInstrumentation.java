package datadog.trace.instrumentation.cats.effect;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class IOShiftInstrumentation extends Instrumenter.Tracing {
  public IOShiftInstrumentation() {
    super("cats-effect", "cats-effect-2");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("cats.effect.internals.IOShift$Tick");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), packageName + ".IOShiftTickAdvice$Constructor");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".IOShiftTickAdvice", packageName + ".IOShiftTickAdvice$Constructor",
    };
  }
}
