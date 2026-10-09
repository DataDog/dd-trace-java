package datadog.trace.agent.test.coverage;

import static datadog.trace.agent.test.coverage.ContextCoverage.inScenario;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.io.BaseEncoding;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import coveragefixture.Fixture;
import coveragefixture.ObservedThread;
import coveragefixture.VisibilityBridgeFixture;
import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextCoverageTest {
  private static final ContextKey<String> KEY = ContextKey.named("coverage-demo");

  @TempDir Path temporary;

  @Test
  void inheritedVisibilityBridgesDoNotCreateUnexpectedMethods() throws Exception {
    VisibilityBridgeFixture fixture = new VisibilityBridgeFixture();
    try (ContextCoverage coverage =
        ContextCoverage.observe(
            VisibilityBridgeFixture.class, VisibilityBridgeFixture.parentType())) {
      inScenario("inherited-method", () -> assertTrue(fixture.inherited()));
      coverage.writeReport(temporary);
    }
    JsonObject report = read(temporary);
    assertHealthy(report);
    assertScenario(report, "inherited-method", "ROOT_CONTEXT");
    assertFalse(report.toString().contains("coveragefixture.VisibilityBridgeFixture.inherited()Z"));
  }

  @Test
  void observesBootstrapJdkClassWithoutChangingItsBehavior() throws Exception {
    FutureTask<Integer> task = new FutureTask<>(() -> 42);
    try (ContextCoverage coverage = ContextCoverage.observe(FutureTask.class)) {
      inScenario("future-task", task::run);
      assertEquals(42, task.get());
      coverage.writeReport(temporary);
    }
    JsonObject report = read(temporary);
    assertHealthy(report);
    assertTrue(report.toString().contains("java.util.concurrent.FutureTask.run()V"));
    assertScenario(report, "future-task", "ROOT_CONTEXT");
  }

  @Test
  void reportsRealLibraryExecutionWithoutCreatingSpans() throws Exception {
    BaseEncoding base64 = BaseEncoding.base64();
    Context request = Context.root().with(KEY, "ordinary-context-value");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Path output = Paths.get(System.getProperty("coverage.output"));
    ContextCoverage coverage =
        ContextCoverage.observe(
            BaseEncoding.class, base64.getClass(), base64.getClass().getSuperclass());
    try (ContextCoverage ignoredCoverage = coverage) {
      inScenario("outside-context", () -> roundTrip(base64));
      try (ContextScope ignored = request.attach()) {
        inScenario("inside-context", () -> roundTrip(base64));
        executor
            .submit(
                coverage.handoff(
                    "ExecutorService.submit → Runnable.run",
                    "async-context-lost",
                    () -> roundTrip(base64)))
            .get(10, SECONDS);

        Runnable restored =
            coverage.handoff(
                "ExecutorService.submit → Runnable.run",
                "async-context-restored",
                () -> roundTrip(base64));
        ContextContinuation continuation = Context.current().capture();
        try {
          executor
              .submit(
                  () -> {
                    try (ContextScope resumed = continuation.resume()) {
                      restored.run();
                    }
                  })
              .get(10, SECONDS);
        } finally {
          continuation.release();
        }

        try (ContextScope suppressed = Context.root().attach()) {
          inScenario("explicit-root", () -> roundTrip(base64));
        }
        assertSame(request, Context.current());
      }
      coverage.compareHandoff(
          "Propagate context across executor submission",
          "async-context-lost",
          "async-context-restored");
      inScenario("after-scope-close", () -> roundTrip(base64));
      executor.submit(() -> roundTrip(base64)).get(10, SECONDS);
      inScenario(
          "exception-outside-context",
          () -> assertThrows(IllegalArgumentException.class, () -> base64.decode("!")));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, SECONDS));
    }
    assertSame(Context.root(), Context.current());
    coverage.writeReport(output);
    JsonObject report = read(output);
    assertHealthy(report);
    JsonObject opportunity = report.getAsJsonArray("opportunities").get(0).getAsJsonObject();
    assertEquals("VERIFIED_GAIN", opportunity.get("status").getAsString());
    assertEquals(11, opportunity.get("recoveredMethodCount").getAsInt());
    assertEquals(12, opportunity.get("recoveredEntries").getAsInt());
    assertTrue(report.toString().contains("ContextCoverageTest.roundTrip"));
    assertScenario(report, "inside-context", "NON_ROOT_CONTEXT");
    assertScenario(report, "async-context-restored", "NON_ROOT_CONTEXT");
    assertScenario(report, "outside-context", "ROOT_CONTEXT");
    assertScenario(report, "async-context-lost", "ROOT_CONTEXT");
    assertScenario(report, "explicit-root", "ROOT_CONTEXT");
    assertScenario(report, "after-scope-close", "ROOT_CONTEXT");
    assertScenario(report, "<unattributed>", "ROOT_CONTEXT");
    assertScenario(report, "exception-outside-context", "ROOT_CONTEXT");
    JsonObject summary = report.getAsJsonObject("summary");
    assertTrue(summary.get("executedMethods").getAsInt() > 0);
    assertTrue(
        summary.get("eligibleMethods").getAsInt() > summary.get("executedMethods").getAsInt());
    assertTrue(Files.size(output.resolve("report.md")) > 0);
  }

  @Test
  void preservesBehaviorAndDistinguishesOverloadsAndUnexecutedMethods() throws Exception {
    Fixture fixture = new Fixture(); // Already loaded before retransformation.
    RuntimeException failure = new RuntimeException("original exception");
    try (ContextCoverage coverage = ContextCoverage.observe(Fixture.class)) {
      assertThrows(IllegalStateException.class, () -> ContextCoverage.observe(Fixture.class));
      inScenario(
          "outer",
          () -> {
            assertEquals(2, fixture.call(1));
            assertSame(
                failure,
                assertThrows(
                    RuntimeException.class,
                    () -> inScenario("inner", () -> fixture.fail(failure))));
            assertEquals("ok", fixture.call("ok"));
          });
      coverage.writeReport(temporary);
    }
    JsonObject report = read(temporary);
    assertHealthy(report);
    assertEquals(4, report.getAsJsonObject("summary").get("eligibleMethods").getAsInt());
    assertEquals(3, report.getAsJsonObject("summary").get("executedMethods").getAsInt());
    assertEquals(2, scenarioCount(report, "outer"));
    assertEquals(1, scenarioCount(report, "inner"));
    assertEquals(2, fixture.call(1));
    // Closing removes the observer; opening another session does not double instrument methods.
    try (ContextCoverage second = ContextCoverage.observe(Fixture.class)) {
      assertEquals(3, fixture.call(2));
      second.writeReport(temporary);
    }
    assertEquals(1, scenarioCount(read(temporary), "<unattributed>"));
  }

  @Test
  void attributesWorkerExecutionToSerializedTestWindowWithoutPropagatingContext() throws Exception {
    Fixture fixture = new Fixture();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Context request = Context.root().with(KEY, "initiating-thread-only");
    ContextCoverage coverage = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignoredCoverage = coverage) {
      coverage.pause();
      try (ContextScope ignored = request.attach()) {
        coverage.resume("spock:example.AsyncSpec#async-request[0]", "async request test");
        executor
            .submit(
                () -> {
                  assertSame(Context.root(), Context.current());
                  fixture.call(1);
                })
            .get(10, SECONDS);
        coverage.pause();
        assertSame(request, Context.current());
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, SECONDS));
    }
    coverage.writeReport(temporary);
    JsonObject report = read(temporary);
    assertHealthy(report);
    assertEquals(1, scenarioCount(report, "async request test"));
    JsonObject observation = firstObservationFor(report, "async request test");
    assertEquals(
        "spock:example.AsyncSpec#async-request[0]", observation.get("scenarioId").getAsString());
    assertEquals("ACTIVE_TEST_WINDOW", observation.get("attribution").getAsString());
    assertEquals("TEMPORAL", observation.get("attributionConfidence").getAsString());
    assertEquals("ROOT_CONTEXT", observation.get("state").getAsString());
    JsonObject scenario = report.getAsJsonArray("scenarios").get(0).getAsJsonObject();
    assertEquals("spock:example.AsyncSpec#async-request[0]", scenario.get("id").getAsString());
    assertEquals("async request test", scenario.get("name").getAsString());
  }

  @Test
  void rejectsContextImplementationTargets() {
    assertThrows(IllegalArgumentException.class, () -> ContextCoverage.observe(Context.class));
  }

  @Test
  void collectsOnlyInsideEnabledTestWindows() throws Exception {
    Fixture fixture = new Fixture();
    ContextCoverage coverage = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignoredCoverage = coverage) {
      coverage.pause();
      fixture.call(0);
      coverage.resume();
      try (ContextScope ignored = Context.root().with(KEY, "feature-body").attach()) {
        fixture.call(1);
      }
      coverage.pause();
      fixture.call(2);
    }
    coverage.writeReport(temporary);
    JsonObject report = read(temporary);
    assertHealthy(report);
    assertEquals(1, report.getAsJsonObject("summary").get("contextEntries").getAsInt());
    assertEquals(0, report.getAsJsonObject("summary").get("rootEntries").getAsInt());
    assertThrows(IllegalStateException.class, coverage::resume);
  }

  @Test
  void attributesOnlyTheTaskAndDoesNotPropagateContext() throws Exception {
    Fixture fixture = new Fixture();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Context context = Context.root().with(KEY, "submission");
    ContextCoverage coverage = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignoredCoverage = coverage) {
      // Same display label outside the handoff must not inflate its estimated reach.
      inScenario("task", () -> fixture.call(0));
      try (ContextScope ignored = context.attach()) {
        Runnable task =
            coverage.handoff(
                "executor",
                "task",
                () -> {
                  assertSame(Context.root(), Context.current());
                  fixture.call(1);
                });
        executor.submit(task).get(10, SECONDS);
        assertSame(context, Context.current());
      }
      executor.submit(() -> fixture.call(2)).get(10, SECONDS);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, SECONDS));
    }
    coverage.writeReport(temporary);
    JsonObject report = read(temporary);
    assertHealthy(report);
    JsonObject candidate = report.getAsJsonArray("opportunities").get(0).getAsJsonObject();
    assertEquals("CANDIDATE", candidate.get("status").getAsString());
    assertEquals(1, candidate.get("rootEntries").getAsInt());
    assertEquals(1, candidate.get("affectedMethodCount").getAsInt());
    JsonObject handoff = report.getAsJsonArray("handoffs").get(0).getAsJsonObject();
    assertTrue(handoff.get("submittedWithContext").getAsBoolean());
    assertFalse(handoff.get("entryWithContext").getAsBoolean());
    assertTrue(handoff.get("completed").getAsBoolean());
    assertEquals(1, scenarioCount(report, "<unattributed>"));
  }

  @Test
  void preservesTaskExceptionsAndRecordsUnstartedWork() throws Exception {
    Fixture fixture = new Fixture();
    RuntimeException failure = new RuntimeException("task failure");
    ContextCoverage coverage = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignoredCoverage = coverage) {
      Runnable task;
      try (ContextScope ignored = Context.root().with(KEY, "submission").attach()) {
        task = coverage.handoff("executor", "failed-task", () -> fixture.fail(failure));
        coverage.handoff("executor", "cancelled-before-start", () -> fixture.call(0));
      }
      assertSame(failure, assertThrows(RuntimeException.class, task::run));
      fixture.call(1);
    }
    coverage.writeReport(temporary);
    JsonObject report = read(temporary);
    assertHealthy(report);
    JsonArray handoffs = report.getAsJsonArray("handoffs");
    assertTrue(handoffs.get(0).getAsJsonObject().get("failed").getAsBoolean());
    assertTrue(handoffs.get(0).getAsJsonObject().get("completed").getAsBoolean());
    assertFalse(handoffs.get(1).getAsJsonObject().get("started").getAsBoolean());
    assertEquals(1, report.getAsJsonArray("opportunities").size());
    assertEquals(
        "INCONCLUSIVE",
        report
            .getAsJsonArray("opportunities")
            .get(0)
            .getAsJsonObject()
            .get("status")
            .getAsString());
    assertEquals(1, scenarioCount(report, "<unattributed>"));
  }

  @Test
  void lateWorkFromAnOldSessionCannotInflateANewOpportunity() throws Exception {
    Fixture fixture = new Fixture();
    Runnable oldTask;
    try (ContextCoverage old = ContextCoverage.observe(Fixture.class)) {
      oldTask = old.handoff("executor", "old", () -> fixture.call("late"));
    }
    ContextCoverage current = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignoredCoverage = current) {
      Runnable task;
      try (ContextScope ignored = Context.root().with(KEY, "submission").attach()) {
        task = current.handoff("executor", "current", () -> fixture.call(1));
      }
      task.run();
      oldTask.run();
    }
    current.writeReport(temporary);
    JsonObject report = read(temporary);
    assertHealthy(report);
    JsonObject candidate = report.getAsJsonArray("opportunities").get(0).getAsJsonObject();
    assertEquals(1, candidate.get("affectedMethodCount").getAsInt());
    assertEquals(1, candidate.get("rootEntries").getAsInt());
    assertEquals(1, scenarioCount(report, "old"));
  }

  @Test
  void excludesReentrantCallsMadeByTheObserver() throws Exception {
    ObservedThread thread = new ObservedThread();
    ContextCoverage coverage = ContextCoverage.observe(ObservedThread.class);
    try (ContextCoverage ignored = coverage) {
      thread.start();
      thread.join(10000);
      assertFalse(thread.isAlive());
    }
    coverage.writeReport(temporary);
    JsonObject report = read(temporary);
    assertHealthy(report);
    // One run() and one application getStackTrace(); collector stack lookups are excluded.
    assertEquals(2, scenarioCount(report, "<unattributed>"));
    assertEquals(2, report.getAsJsonObject("summary").get("executedMethods").getAsInt());
  }

  private static void roundTrip(BaseEncoding encoding) {
    assertEquals(
        "hello", new String(encoding.decode(encoding.encode("hello".getBytes(UTF_8))), UTF_8));
  }

  private static JsonObject read(Path directory) throws Exception {
    try (Reader reader = Files.newBufferedReader(directory.resolve("report.json"), UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  private static void assertHealthy(JsonObject report) {
    assertEquals(0, report.getAsJsonObject("health").getAsJsonArray("errors").size());
    assertEquals(0, report.getAsJsonObject("health").get("droppedObservations").getAsLong());
    assertEquals(10000, report.getAsJsonObject("health").get("maxDistinctObservations").getAsInt());
  }

  private static long scenarioCount(JsonObject report, String scenario) {
    long count = 0;
    for (com.google.gson.JsonElement method : report.getAsJsonArray("methods")) {
      for (com.google.gson.JsonElement item :
          method.getAsJsonObject().getAsJsonArray("observations")) {
        JsonObject observation = item.getAsJsonObject();
        if (scenario.equals(observation.get("scenario").getAsString())) {
          count += observation.get("count").getAsLong();
        }
      }
    }
    return count;
  }

  private static JsonObject firstObservationFor(JsonObject report, String scenario) {
    for (com.google.gson.JsonElement method : report.getAsJsonArray("methods")) {
      for (com.google.gson.JsonElement item :
          method.getAsJsonObject().getAsJsonArray("observations")) {
        JsonObject observation = item.getAsJsonObject();
        if (scenario.equals(observation.get("scenario").getAsString())) {
          return observation;
        }
      }
    }
    throw new AssertionError("No observations for " + scenario);
  }

  private static void assertScenario(JsonObject report, String scenario, String state) {
    boolean found = false;
    for (com.google.gson.JsonElement method : report.getAsJsonArray("methods")) {
      JsonArray observations = method.getAsJsonObject().getAsJsonArray("observations");
      for (com.google.gson.JsonElement item : observations) {
        JsonObject observation = item.getAsJsonObject();
        if (scenario.equals(observation.get("scenario").getAsString())) {
          found = true;
          assertEquals(state, observation.get("state").getAsString(), scenario);
          assertTrue(observation.get("count").getAsLong() > 0);
          assertFalse(observation.getAsJsonArray("stack").isEmpty());
        }
      }
    }
    assertTrue(found, "No observations for " + scenario);
  }
}
