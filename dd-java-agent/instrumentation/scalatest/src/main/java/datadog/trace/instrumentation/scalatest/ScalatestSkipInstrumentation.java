package datadog.trace.instrumentation.scalatest;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.bootstrap.InstrumentationContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.scalatest.Filter;
import org.scalatest.Tracker;
import scala.Tuple2;

@AutoService(InstrumenterModule.class)
public class ScalatestSkipInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public ScalatestSkipInstrumentation() {
    super("ci-visibility", "scalatest");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"org.scalatest.Filter", "org.scalatest.Args"};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ScalatestUtils",
      packageName + ".RunContext",
      packageName + ".DatadogReporter",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.scalatest.Filter", packageName + ".RunContext");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // org.scalatest.Args
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(2, named("org.scalatest.Filter")))
            .and(takesArgument(5, named("org.scalatest.Tracker"))),
        ScalatestSkipInstrumentation.class.getName() + "$ArgsContructorAdvice");
    // org.scalatest.Filter
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(3))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, named("scala.collection.immutable.Map")))
            .and(takesArgument(2, String.class)),
        ScalatestSkipInstrumentation.class.getName() + "$SingleTestFilterAdvice");
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(3))
            .and(takesArgument(0, named("scala.collection.immutable.Set")))
            .and(takesArgument(1, named("scala.collection.immutable.Map")))
            .and(takesArgument(2, String.class)),
        ScalatestSkipInstrumentation.class.getName() + "$MultipleTestsFilterAdvice");
  }

  public static class ArgsContructorAdvice {
    @Advice.OnMethodExit
    public static void apply(
        @Advice.Argument(value = 2) Filter filter, @Advice.Argument(value = 5) Tracker tracker) {
      int runStamp = tracker.nextOrdinal().runStamp();
      RunContext context = RunContext.getOrCreate(runStamp);
      RunContext existingContext =
          InstrumentationContext.get(Filter.class, RunContext.class).putIfAbsent(filter, context);
      if (existingContext != context) {
        // This shouldn't happen.
        // If it does, instrumentation isn't working as expected, or Scalatest internals changed.
        // Either of the two means associating filters with runs should be done differently.
        throw new IllegalStateException(
            "Attempting to associate filter "
                + filter
                + " with runstamp "
                + runStamp
                + ", while already associated with "
                + existingContext.getRunStamp());
      }
    }
  }

  public static class SingleTestFilterAdvice {
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "filterResult is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void apply(
        @Advice.This Filter filter,
        @Advice.Return(readOnly = false) Tuple2<Boolean, Boolean> filterResult,
        @Advice.Argument(value = 0) String testName,
        @Advice.Argument(value = 1)
            scala.collection.immutable.Map<String, scala.collection.immutable.Set<String>> tags,
        @Advice.Argument(value = 2) String suiteId) {
      if (filterResult == null // filter terminated exceptionally
          || filterResult._1() // test is filtered
          || filterResult._2() // test is ignored
      ) {
        return;
      }
      TestIdentifier test = new TestIdentifier(suiteId, testName, null);
      RunContext runContext =
          InstrumentationContext.get(Filter.class, RunContext.class).get(filter);
      runContext.populateTags(test, tags);

      if (runContext.skip(test, tags)) {
        filterResult = new Tuple2<>(false, true);
      }
    }
  }

  public static class MultipleTestsFilterAdvice {
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "filterResult is the return value of the instrumented method")
    @Advice.OnMethodExit
    public static void apply(
        @Advice.This Filter filter,
        @Advice.Return(readOnly = false)
            scala.collection.immutable.List<Tuple2<String, Boolean>> filterResult,
        @Advice.Argument(value = 1)
            scala.collection.immutable.Map<String, scala.collection.immutable.Set<String>> tags,
        @Advice.Argument(value = 2) String suiteId) {
      if (filterResult == null /* filter terminated exceptionally */) {
        return;
      }
      RunContext runContext =
          InstrumentationContext.get(Filter.class, RunContext.class).get(filter);
      runContext.populateTags(suiteId, tags, filterResult);

      filterResult = runContext.skip(suiteId, filterResult);
    }
  }
}
