package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import scala.Option;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map;
import scala.collection.immutable.Set;

public class RunContext {

  private static final ConcurrentMap<Integer, RunContext> CONTEXTS = new ConcurrentHashMap<>();

  public static RunContext getOrCreate(int runStamp) {
    return CONTEXTS.computeIfAbsent(runStamp, RunContext::new);
  }

  public static void destroy(int runStamp) {
    RunContext context = CONTEXTS.remove(runStamp);
    context.destroy();
  }

  private final int runStamp;
  private final TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler =
      InstrumentationBridge.createTestEventsHandler("scalatest", null, null);
  private final java.util.Map<TestIdentifier, SkipReason> skipReasonByTest =
      new ConcurrentHashMap<>();
  private final java.util.Set<TestIdentifier> itrUnskippableTests = ConcurrentHashMap.newKeySet();
  private final java.util.Map<TestIdentifier, TestExecutionPolicy> executionPolicies =
      new ConcurrentHashMap<>();

  public RunContext(int runStamp) {
    this.runStamp = runStamp;
  }

  public int getRunStamp() {
    return runStamp;
  }

  public TestEventsHandler<TestSuiteDescriptor, TestDescriptor> getEventHandler() {
    return eventHandler;
  }

  public SkipReason getSkipReason(TestIdentifier test) {
    return skipReasonByTest.remove(test);
  }

  public boolean itrUnskippable(TestIdentifier test) {
    return itrUnskippableTests.remove(test);
  }

  public scala.collection.immutable.List<Tuple2<String, Boolean>> skip(
      String suiteId,
      Map<String, Set<String>> tags,
      scala.collection.immutable.List<Tuple2<String, Boolean>> namesAndSkipStatuses) {
    java.util.List<Tuple2<String, Boolean>> modifiedNamesAndSkipStatuses =
        new ArrayList<>(namesAndSkipStatuses.size());
    Iterator<Tuple2<String, Boolean>> it = namesAndSkipStatuses.iterator();
    while (it.hasNext()) {
      Tuple2<String, Boolean> nameAndSkipStatus = skip(suiteId, tags, it.next());
      modifiedNamesAndSkipStatuses.add(nameAndSkipStatus);
    }
    return JavaConverters.asScalaBuffer(modifiedNamesAndSkipStatuses).toList();
  }

  private Tuple2<String, Boolean> skip(
      String suiteId,
      Map<String, Set<String>> tags,
      Tuple2<String, Boolean> testNameAndSkipStatus) {
    if (testNameAndSkipStatus._2()) {
      return testNameAndSkipStatus; // test already skipped
    }

    String testName = testNameAndSkipStatus._1();
    TestIdentifier test = new TestIdentifier(suiteId, testName, null);

    boolean itrUnskippable = isItrUnskippable(test, tags);
    if (itrUnskippable) {
      itrUnskippableTests.add(test);
    }

    SkipReason skipReason = eventHandler.skipReason(test);
    if (skipReason == null || skipReason == SkipReason.ITR && itrUnskippable) {
      return testNameAndSkipStatus;
    }

    skipReasonByTest.put(test, skipReason);
    return new Tuple2<>(testName, true);
  }

  public boolean skip(TestIdentifier test, Map<String, Set<String>> tags) {
    boolean itrUnskippable = isItrUnskippable(test, tags);
    if (itrUnskippable) {
      itrUnskippableTests.add(test);
    }

    SkipReason skipReason = eventHandler.skipReason(test);
    if (skipReason == null) {
      return false;
    }

    if (skipReason == SkipReason.ITR && itrUnskippable) {
      return false;
    }

    skipReasonByTest.put(test, skipReason);
    return true;
  }

  private boolean isItrUnskippable(TestIdentifier test, Map<String, Set<String>> tags) {
    Option<Set<String>> testTagsOption = tags.get(test.getName());
    if (testTagsOption.isEmpty()) {
      return false;
    }
    Set<String> testTags = testTagsOption.get();
    return testTags != null && testTags.contains(InstrumentationBridge.ITR_UNSKIPPABLE_TAG);
  }

  public TestExecutionPolicy executionPolicy(
      TestIdentifier testIdentifier, TestSourceData testSourceData) {
    return executionPolicies.computeIfAbsent(
        testIdentifier, test -> eventHandler.executionPolicy(test, testSourceData));
  }

  @Nullable
  public TestExecutionPolicy popExecutionPolicy(TestIdentifier testIdentifier) {
    TestExecutionPolicy[] holder = new TestExecutionPolicy[1];
    executionPolicies.computeIfPresent(
        testIdentifier,
        (ti, policy) -> {
          holder[0] = policy;
          return policy.applicable() ? policy : null;
        });
    return holder[0];
  }

  public void destroy() {
    eventHandler.close();
  }
}
