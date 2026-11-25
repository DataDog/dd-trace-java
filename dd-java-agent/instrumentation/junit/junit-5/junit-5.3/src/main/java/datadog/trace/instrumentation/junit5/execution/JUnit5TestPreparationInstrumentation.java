package datadog.trace.instrumentation.junit5.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.TestDescriptor;

/**
 * Opens a coverage window when a test node starts preparing, so that the test is credited with the
 * lines that run before the framework reports it started: Jupiter builds the test instance there.
 */
@AutoService(InstrumenterModule.class)
public class JUnit5TestPreparationInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JUnit5TestPreparationInstrumentation() {
    super("ci-visibility", "junit-5");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityCoverageLineProbesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.junit.platform.engine.support.hierarchical.NodeTestTask";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("org.junit.platform.engine.support.hierarchical.NodeTestTask")
          .withField(
              new String[0], 0, "testDescriptor", "Lorg/junit/platform/engine/TestDescriptor;")
          .withMethod(new String[0], 0, "prepare", "V")
          .build()
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("prepare").and(takesNoArguments()),
        JUnit5TestPreparationInstrumentation.class.getName() + "$PrepareAdvice");
  }

  public static class PrepareAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void openPreparationWindow(
        @Advice.FieldValue("testDescriptor") TestDescriptor testDescriptor) {
      if (testDescriptor.isTest()) {
        TestPreparationWindow.open();
      }
    }
  }
}
