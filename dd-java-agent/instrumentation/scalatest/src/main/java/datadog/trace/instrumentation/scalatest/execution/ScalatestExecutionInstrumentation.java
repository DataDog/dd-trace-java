package datadog.trace.instrumentation.scalatest.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.instrumentation.scalatest.RunContext;
import datadog.trace.instrumentation.scalatest.ScalatestUtils;
import datadog.trace.util.Strings;
import java.lang.invoke.MethodHandle;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.scalatest.Args;
import org.scalatest.Outcome;
import org.scalatest.Status;
import org.scalatest.Suite;
import org.scalatest.SuperEngine;

@AutoService(InstrumenterModule.class)
public class ScalatestExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private final String parentPackageName = Strings.getPackageName(ScalatestUtils.class.getName());

  public ScalatestExecutionInstrumentation() {
    super("ci-visibility", "scalatest", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.scalatest.SuperEngine";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".ScalatestUtils",
      parentPackageName + ".RunContext",
      parentPackageName + ".DatadogReporter",
      packageName + ".SuppressedTestFailedException",
      packageName + ".TestExecutionWrapper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("runTestImpl")
            .and(takesArgument(0, named("org.scalatest.Suite")))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, named("org.scalatest.Args"))),
        ScalatestExecutionInstrumentation.class.getName() + "$ExecutionAdvice");
  }

  public static class ExecutionAdvice {
    @Advice.OnMethodEnter
    public static void beforeTest(
        @Advice.Argument(value = 0) Suite suite,
        @Advice.Argument(value = 1) String testName,
        @Advice.Argument(value = 2) Args args,
        @Advice.Argument(value = 4, readOnly = false)
            scala.Function1<SuperEngine<?>.TestLeaf, Outcome> invokeWithFixture)
        throws Throwable {
      if (!(invokeWithFixture instanceof TestExecutionWrapper)) {
        int runStamp = args.tracker().nextOrdinal().runStamp();
        RunContext context = RunContext.getOrCreate(runStamp);
        TestIdentifier testIdentifier = new TestIdentifier(suite.suiteId(), testName, null);
        TestSourceData testSourceData = new TestSourceData(suite.getClass(), null, null);
        TestExecutionPolicy executionPolicy =
            context.getOrCreateExecutionPolicy(
                testIdentifier, testSourceData, context.tags(testIdentifier));

        invokeWithFixture = new TestExecutionWrapper(invokeWithFixture, executionPolicy);
      }
    }

    @Advice.OnMethodExit
    public static void afterTest(
        @Advice.Origin MethodHandle runTest,
        @Advice.This SuperEngine engine,
        @Advice.Argument(value = 0) Suite suite,
        @Advice.Argument(value = 1) String testName,
        @Advice.Argument(value = 2) Args args,
        @Advice.Argument(value = 3) Object includeIcon,
        @Advice.Argument(value = 4)
            scala.Function1<SuperEngine<?>.TestLeaf, Outcome> invokeWithFixture,
        @Advice.Return(readOnly = false) Status status)
        throws Throwable {
      TestExecutionWrapper invokeWrapper = (TestExecutionWrapper) invokeWithFixture;
      if (invokeWrapper.applicable()) {
        status =
            (Status)
                runTest.invokeWithArguments(
                    engine, suite, testName, args, includeIcon, invokeWithFixture);
      }
    }
  }
}
