package datadog.trace.instrumentation.junit5.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.coverage.SuiteSetupWindow;
import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import java.util.Optional;
import net.bytebuddy.asm.Advice;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * Opens the coverage windows of the work that JUnit Platform engines built on its hierarchical
 * engine (Jupiter, Spock 2) do outside a test's own run: a test's preparation, where Jupiter builds
 * the test instance before it reports the test started, and a suite's setup, where a class runs its
 * {@code @BeforeAll} methods and a test template computes its first arguments.
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
          .withMethod(new String[0], 0, "executeRecursively", "V")
          .withMethod(
              new String[0],
              0,
              "setParentContext",
              "V",
              "Lorg/junit/platform/engine/support/hierarchical/EngineExecutionContext;")
          .build()
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("prepare").and(takesNoArguments()),
        JUnit5TestPreparationInstrumentation.class.getName() + "$PrepareAdvice");
    transformer.applyAdvice(
        named("executeRecursively").and(takesNoArguments()),
        JUnit5TestPreparationInstrumentation.class.getName() + "$ExecuteAdvice");
    transformer.applyAdvice(
        named("setParentContext").and(takesArguments(1)),
        JUnit5TestPreparationInstrumentation.class.getName() + "$ChildStartsAdvice");
  }

  public static class PrepareAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void openPreparationWindow(
        @Advice.FieldValue("testDescriptor") TestDescriptor testDescriptor) {
      if (testDescriptor.isTest()) {
        Optional<TestDescriptor> parent = testDescriptor.getParent();
        TestPreparationWindow.open(parent.isPresent() ? parent.get() : null);
      }
    }
  }

  /** A node runs: a container's setup starts, and ends at the latest when the container ends. */
  public static class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void openSuiteSetupWindow(
        @Advice.FieldValue("testDescriptor") TestDescriptor testDescriptor) {
      if (testDescriptor.isTest()) {
        return;
      }
      Optional<TestDescriptor> parent = testDescriptor.getParent();
      Optional<TestSource> source = testDescriptor.getSource();
      // classes and test templates run setup for their tests; engines and other containers do not
      boolean hasSetup =
          source.isPresent()
              && (source.get() instanceof ClassSource || source.get() instanceof MethodSource);
      SuiteSetupWindow.open(testDescriptor, parent.isPresent() ? parent.get() : null, hasSetup);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishSuite(
        @Advice.FieldValue("testDescriptor") TestDescriptor testDescriptor) {
      if (!testDescriptor.isTest()) {
        SuiteSetupWindow.finish(testDescriptor);
      }
    }
  }

  /**
   * A container hands its context to a child just before the child runs, on the container's thread,
   * after its own setup: static children once {@code before} and {@code execute} returned, dynamic
   * children (test template invocations, dynamic tests) from within {@code execute}.
   */
  public static class ChildStartsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void endSuiteSetup(
        @Advice.FieldValue("testDescriptor") TestDescriptor testDescriptor) {
      Optional<TestDescriptor> parent = testDescriptor.getParent();
      if (parent.isPresent()) {
        SuiteSetupWindow.childStarts(parent.get());
      }
    }
  }
}
