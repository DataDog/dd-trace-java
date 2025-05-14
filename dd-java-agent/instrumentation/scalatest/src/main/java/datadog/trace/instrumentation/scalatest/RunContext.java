package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

  public static RunContext get(int runStamp) {
    return CONTEXTS.get(runStamp);
  }

  public static void destroy(int runStamp) {
    RunContext context = CONTEXTS.remove(runStamp);
    if (context != null) {
      context.destroy();
    }
  }

  private final int runStamp;

  private final TestEventsHandler<TestSuiteDescriptor, TestDescriptor> eventHandler =
      InstrumentationBridge.createTestEventsHandler(
          "scalatest", null, null, ScalatestUtils.CAPABILITIES);

  private final java.util.Map<TestIdentifier, SkipReason> skipReasonByTest =
      new ConcurrentHashMap<>();
  private final java.util.Map<TestIdentifier, Collection<String>> tagsByTest =
      new ConcurrentHashMap<>();
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
    return tagsByTest
        .getOrDefault(test, Collections.emptyList())
        .contains(CIConstants.Tags.ITR_UNSKIPPABLE_TAG);
  }

  public Collection<String> tags(TestIdentifier test) {
    return tagsByTest.getOrDefault(test, Collections.emptyList());
  }

  public void populateTags(
      String suiteId,
      Map<String, Set<String>> tags,
      scala.collection.immutable.List<Tuple2<String, Boolean>> namesAndSkipStatuses) {
    Iterator<Tuple2<String, Boolean>> it = namesAndSkipStatuses.iterator();
    while (it.hasNext()) {
      Tuple2<String, Boolean> nameAndSkipStatus = it.next();
      String testName = nameAndSkipStatus._1();
      TestIdentifier test = new TestIdentifier(suiteId, testName, null);
      populateTags(test, tags);
    }
  }

  public scala.collection.immutable.List<Tuple2<String, Boolean>> skip(
      String suiteId,
      scala.collection.immutable.List<Tuple2<String, Boolean>> namesAndSkipStatuses) {
    java.util.List<Tuple2<String, Boolean>> modifiedNamesAndSkipStatuses =
        new ArrayList<>(namesAndSkipStatuses.size());
    Iterator<Tuple2<String, Boolean>> it = namesAndSkipStatuses.iterator();
    while (it.hasNext()) {
      Tuple2<String, Boolean> nameAndSkipStatus = skip(suiteId, it.next());
      modifiedNamesAndSkipStatuses.add(nameAndSkipStatus);
    }
    return JavaConverters.asScalaBuffer(modifiedNamesAndSkipStatuses).toList();
  }

  private Tuple2<String, Boolean> skip(
      String suiteId, Tuple2<String, Boolean> testNameAndSkipStatus) {
    if (testNameAndSkipStatus._2()) {
      return testNameAndSkipStatus; // test already skipped
    }

    String testName = testNameAndSkipStatus._1();
    TestIdentifier test = new TestIdentifier(suiteId, testName, null);

    SkipReason skipReason = eventHandler.skipReason(test);
    if (skipReason == null || skipReason == SkipReason.ITR && itrUnskippable(test)) {
      return testNameAndSkipStatus;
    }

    skipReasonByTest.put(test, skipReason);
    return new Tuple2<>(testName, true);
  }

  public void populateTags(TestIdentifier test, Map<String, Set<String>> tags) {
    Option<Set<String>> testTagsOption = tags.get(test.getName());
    if (testTagsOption.isEmpty()) {
      return;
    }

    Set<String> testTags = testTagsOption.get();
    if (testTags != null) {
      tagsByTest.put(test, JavaConverters.asJavaCollection(testTags));
    }
  }

  public boolean skip(TestIdentifier test, Map<String, Set<String>> tags) {
    SkipReason skipReason = eventHandler.skipReason(test);
    if (skipReason == null) {
      return false;
    }

    if (skipReason == SkipReason.ITR && itrUnskippable(test)) {
      return false;
    }

    skipReasonByTest.put(test, skipReason);
    return true;
  }

  public TestExecutionPolicy getOrCreateExecutionPolicy(
      TestIdentifier testIdentifier, TestSourceData testSourceData, Collection<String> testTags) {
    return executionPolicies.computeIfAbsent(
        testIdentifier, test -> eventHandler.executionPolicy(test, testSourceData, testTags));
  }

  @Nullable
  public TestExecutionHistory getExecutionHistory(TestIdentifier testIdentifier) {
    return executionPolicies.get(testIdentifier);
  }

  @Nullable
  public TestExecutionHistory popExecutionHistory(TestIdentifier testIdentifier) {
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
