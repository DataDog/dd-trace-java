package datadog.trace.instrumentation.scalatest;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
  private final TestEventsHandler eventHandler =
      InstrumentationBridge.createTestEventsHandler("scalatest", Paths.get("").toAbsolutePath());
  private final java.util.Set<SkippableTest> skippedTests = ConcurrentHashMap.newKeySet();
  private final java.util.Set<SkippableTest> unskippableTests = ConcurrentHashMap.newKeySet();

  public RunContext(int runStamp) {
    this.runStamp = runStamp;
  }

  public int getRunStamp() {
    return runStamp;
  }

  public TestEventsHandler getEventHandler() {
    return eventHandler;
  }

  public boolean skipped(SkippableTest test) {
    return skippedTests.remove(test);
  }

  public boolean unskippable(SkippableTest test) {
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
    SkippableTest test = new SkippableTest(suiteId, testName, null, null);
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

  public boolean skip(SkippableTest test, Map<String, Set<String>> tags) {
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

  public boolean isUnskippable(SkippableTest test, Map<String, Set<String>> tags) {
    Option<Set<String>> testTagsOption = tags.get(test.getName());
    if (testTagsOption.isEmpty()) {
      return false;
    }
    Set<String> testTags = testTagsOption.get();
    return testTags != null && testTags.contains(InstrumentationBridge.ITR_UNSKIPPABLE_TAG);
  }

  public void destroy() {
    eventHandler.close();
  }
}
