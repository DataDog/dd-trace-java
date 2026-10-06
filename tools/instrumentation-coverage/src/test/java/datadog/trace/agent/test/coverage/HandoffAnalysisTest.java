package datadog.trace.agent.test.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HandoffAnalysisTest {
  @Test
  void measuresRecoveryForTheSameWorkload() {
    JsonObject report = paired();
    observation(report, "decode", "baseline", 2, 0);
    observation(report, "encode", "baseline", 1, 0);
    observation(report, "decode", "intervention", 0, 2);
    observation(report, "encode", "intervention", 0, 1);
    JsonObject result = result(report, "VERIFIED_GAIN");
    assertEquals(2, result.get("affectedMethodCount").getAsInt());
    assertEquals(2, result.get("recoveredMethodCount").getAsInt());
    assertEquals(3, result.get("recoveredEntries").getAsInt());
    assertEquals(3, result.get("baselineRootEntries").getAsInt());
    assertEquals(3, result.get("interventionContextEntries").getAsInt());
    assertEquals("decode", result.getAsJsonArray("recoveredMethods").get(0).getAsString());
    assertEquals("caller.submit", result.getAsJsonArray("submissionStack").get(0).getAsString());
  }

  @Test
  void missingExperimentRemainsACandidate() {
    JsonObject report = paired();
    report.add("experiments", new JsonArray());
    observation(report, "decode", "baseline", 2, 0);
    JsonObject result = result(report, "CANDIDATE");
    assertEquals(0, result.get("recoveredEntries").getAsInt());
    assertEquals("", result.get("interventionScenario").getAsString());
  }

  @Test
  void excludesRootSubmissionsAndUnfinishedBaselines() {
    JsonObject report = paired();
    observation(report, "decode", "baseline", 2, 0);
    handoff(report, 0).addProperty("submittedWithContext", false);
    HandoffAnalysis.analyze(report);
    assertTrue(report.getAsJsonArray("opportunities").isEmpty());
    handoff(report, 0).addProperty("submittedWithContext", true);
    handoff(report, 0).addProperty("completed", false);
    HandoffAnalysis.analyze(report);
    assertTrue(report.getAsJsonArray("opportunities").isEmpty());
  }

  @Test
  void rejectsPerMethodMismatchEvenWhenTotalInvocationCountMatches() {
    JsonObject report = paired();
    observation(report, "decode", "baseline", 2, 0);
    observation(report, "encode", "baseline", 1, 0);
    observation(report, "decode", "intervention", 0, 1);
    observation(report, "encode", "intervention", 0, 2);
    assertEquals(0, result(report, "INCONCLUSIVE").get("recoveredEntries").getAsInt());
  }

  @Test
  void droppedObservationsAndLiveSnapshotsCannotConfirmGain() {
    JsonObject report = recovered();
    report.getAsJsonObject("health").addProperty("droppedObservations", 1);
    result(report, "INCONCLUSIVE");
    report.getAsJsonObject("health").addProperty("droppedObservations", 0);
    report.addProperty("finalized", false);
    result(report, "INCONCLUSIVE");
  }

  @Test
  void incompleteOrFailedTasksCannotConfirmGain() {
    JsonObject report = recovered();
    handoff(report, 1).addProperty("completed", false);
    result(report, "INCONCLUSIVE");
    handoff(report, 1).addProperty("completed", true);
    handoff(report, 1).addProperty("failed", true);
    result(report, "INCONCLUSIVE");
    handoff(report, 1).addProperty("failed", false);
    handoff(report, 0).addProperty("failed", true);
    result(report, "INCONCLUSIVE");
  }

  @Test
  void multipleHandoffsCannotSilentlyShareScenarioAttribution() {
    JsonObject report = recovered();
    report.getAsJsonArray("handoffs").add(handoff(report, 1).deepCopy());
    result(report, "INCONCLUSIVE");
    report.getAsJsonArray("handoffs").remove(2);
    report.getAsJsonArray("handoffs").add(handoff(report, 0).deepCopy());
    HandoffAnalysis.analyze(report);
    assertEquals(2, report.getAsJsonArray("opportunities").size());
    report
        .getAsJsonArray("opportunities")
        .forEach(
            o -> assertEquals("INCONCLUSIVE", o.getAsJsonObject().get("status").getAsString()));
  }

  @Test
  void restoredBoundaryWithoutDownstreamRecoveryHasNoGain() {
    JsonObject report = paired();
    observation(report, "decode", "baseline", 2, 0);
    observation(report, "decode", "intervention", 2, 0);
    assertEquals(0, result(report, "NO_GAIN").get("recoveredEntries").getAsInt());
  }

  @Test
  void regressionInOneMethodPreventsClaimingGainFromAnother() {
    JsonObject report = recovered();
    observation(report, "encode", "baseline", 0, 1);
    observation(report, "encode", "intervention", 1, 0);
    JsonObject result = result(report, "NO_GAIN");
    assertEquals(0, result.get("recoveredEntries").getAsInt());
    assertEquals(1, result.get("regressedEntries").getAsInt());
    assertEquals(1, result.get("regressedMethodCount").getAsInt());
  }

  @Test
  void partialRecoveryCountsEntriesWithoutClaimingFullyRecoveredMethods() {
    JsonObject report = paired();
    observation(report, "decode", "baseline", 2, 0);
    observation(report, "decode", "intervention", 1, 1);
    JsonObject result = result(report, "VERIFIED_GAIN");
    assertEquals(1, result.get("recoveredEntries").getAsInt());
    assertEquals(0, result.get("recoveredMethodCount").getAsInt());
  }

  @Test
  void ranksVerifiedGainsBeforeLargerCandidatesAndUsesStableTies() {
    JsonObject report = recovered();
    for (String scenario : new String[] {"z-candidate", "a-candidate"}) {
      JsonObject candidate = handoff(report, 0).deepCopy();
      candidate.addProperty("scenario", scenario);
      candidate.addProperty("handoffId", scenario);
      report.getAsJsonArray("handoffs").add(candidate);
      observation(report, "decode", scenario, 10, 0);
      observation(report, "encode", scenario, 10, 0);
    }
    HandoffAnalysis.analyze(report);
    JsonArray opportunities = report.getAsJsonArray("opportunities");
    assertEquals(3, opportunities.size());
    assertEquals(
        "VERIFIED_GAIN", opportunities.get(0).getAsJsonObject().get("status").getAsString());
    assertEquals(
        "a-candidate",
        opportunities.get(1).getAsJsonObject().get("baselineScenario").getAsString());
    assertEquals(
        "z-candidate",
        opportunities.get(2).getAsJsonObject().get("baselineScenario").getAsString());
  }

  @Test
  void excludesUnrelatedWorkWithTheSameScenarioLabel() {
    JsonObject report = recovered();
    observation(report, "unrelated", "baseline", 100, 0);
    JsonArray methods = report.getAsJsonArray("methods");
    methods
        .get(methods.size() - 1)
        .getAsJsonObject()
        .getAsJsonArray("observations")
        .forEach(o -> o.getAsJsonObject().addProperty("handoffId", ""));
    JsonObject result = result(report, "VERIFIED_GAIN");
    assertEquals(1, result.get("affectedMethodCount").getAsInt());
    assertEquals(2, result.get("recoveredEntries").getAsInt());
    assertEquals(2, result.get("baselineRootEntries").getAsInt());
  }

  private static JsonObject recovered() {
    JsonObject report = paired();
    observation(report, "decode", "baseline", 2, 0);
    observation(report, "decode", "intervention", 0, 2);
    return report;
  }

  private static JsonObject paired() {
    return JsonParser.parseString(
            """
        {
          "finalized": true,
          "health": {"droppedObservations": 0},
          "methods": [],
          "handoffs": [
            {"handoffId": "h1", "boundary": "Executor.submit", "scenario": "baseline",
             "submittedWithContext": true, "started": true, "entryWithContext": false,
             "completed": true, "failed": false, "submissionStack": ["caller.submit"]},
            {"handoffId": "h2", "boundary": "Executor.submit", "scenario": "intervention",
             "submittedWithContext": true, "started": true, "entryWithContext": true,
             "completed": true, "failed": false, "submissionStack": ["caller.submit"]}
          ],
          "experiments": [{"name": "propagate executor", "baselineScenario": "baseline",
                           "interventionScenario": "intervention"}]
        }
        """)
        .getAsJsonObject();
  }

  private static JsonObject handoff(JsonObject report, int index) {
    return report.getAsJsonArray("handoffs").get(index).getAsJsonObject();
  }

  private static void observation(
      JsonObject report, String methodId, String scenario, long root, long context) {
    JsonObject method = new JsonObject();
    method.addProperty("method", methodId);
    JsonArray observations = new JsonArray();
    for (String state : new String[] {"ROOT_CONTEXT", "NON_ROOT_CONTEXT"}) {
      JsonObject observation = new JsonObject();
      observation.addProperty("scenario", scenario);
      observation.addProperty(
          "handoffId",
          scenario.equals("baseline") ? "h1" : scenario.equals("intervention") ? "h2" : scenario);
      observation.addProperty("state", state);
      observation.addProperty("count", state.equals("ROOT_CONTEXT") ? root : context);
      observations.add(observation);
    }
    method.add("observations", observations);
    report.getAsJsonArray("methods").add(method);
  }

  private static JsonObject result(JsonObject report, String status) {
    HandoffAnalysis.analyze(report);
    assertEquals(1, report.getAsJsonArray("opportunities").size());
    JsonObject result = report.getAsJsonArray("opportunities").get(0).getAsJsonObject();
    assertEquals(status, result.get("status").getAsString());
    return result;
  }
}
