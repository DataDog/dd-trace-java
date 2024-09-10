package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
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
  private final java.util.Set<TestIdentifier> skippedTests = ConcurrentHashMap.newKeySet();
  private final java.util.Set<TestIdentifier> unskippableTests = ConcurrentHashMap.newKeySet();
  private final java.util.Map<TestIdentifier, TestRetryPolicy> retryPolicies =
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

  public boolean skipped(TestIdentifier test) {
    return skippedTests.remove(test);
  }

  public boolean unskippable(TestIdentifier test) {
    return unskippableTests.remove(test);
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
    if (isUnskippable(test, tags)) {
      unskippableTests.add(test);
      return testNameAndSkipStatus;

    } else if (eventHandler.skip(test)) {
      skippedTests.add(test);
      return new Tuple2<>(testName, true);

    } else {
      return testNameAndSkipStatus;
    }
  }

  public boolean skip(TestIdentifier test, Map<String, Set<String>> tags) {
    if (isUnskippable(test, tags)) {
      unskippableTests.add(test);
      return false;
    } else if (eventHandler.skip(test)) {
      skippedTests.add(test);
      return true;
    } else {
      return false;
    }
  }

  public boolean isUnskippable(TestIdentifier test, Map<String, Set<String>> tags) {
    Option<Set<String>> testTagsOption = tags.get(test.getName());
    if (testTagsOption.isEmpty()) {
      return false;
    }
    Set<String> testTags = testTagsOption.get();
    return testTags != null && testTags.contains(InstrumentationBridge.ITR_UNSKIPPABLE_TAG);
  }

  public TestRetryPolicy retryPolicy(TestIdentifier testIdentifier) {
    return retryPolicies.computeIfAbsent(testIdentifier, eventHandler::retryPolicy);
  }

  @Nullable
  public TestRetryPolicy popRetryPolicy(TestIdentifier testIdentifier) {
    TestRetryPolicy[] holder = new TestRetryPolicy[1];
    retryPolicies.computeIfPresent(
        testIdentifier,
        (ti, retryPolicy) -> {
          holder[0] = retryPolicy;
          return retryPolicy.retriesLeft() ? retryPolicy : null;
        });
    return holder[0];
  }

  public void destroy() {
    eventHandler.close();
  }
}
