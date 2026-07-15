package datadog.trace.instrumentation.karate2;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Registers a {@code io.karatelabs.core.RunListener} on every {@code
 * io.karatelabs.core.Runner.Builder}.
 *
 * <p>This module is compiled for Java 8 so the agent can enumerate it on any JVM; the advice and
 * helper classes that reference the Java 21 {@code io.karatelabs} API live in the {@code java21}
 * source set and are referenced by name.
 */
@AutoService(InstrumenterModule.class)
public class KarateInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KarateInstrumentation() {
    super("ci-visibility", "karate");
  }

  @Override
  public String instrumentedType() {
    return "io.karatelabs.core.Runner$Builder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KarateUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".KarateTracingListener",
      packageName + ".KarateBuilderAdvice"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".KarateBuilderAdvice");
  }
}
