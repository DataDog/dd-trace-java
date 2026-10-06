package datadog.trace.agent.test.coverage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Ranks observed losses; verified gains apply only to the paired, measured workload. */
final class HandoffAnalysis {
  private HandoffAnalysis() {}

  static void analyze(JsonObject report) {
    List<JsonObject> opportunities = new ArrayList<>();
    for (JsonElement element : report.getAsJsonArray("handoffs")) {
      JsonObject handoff = element.getAsJsonObject();
      if (!flag(handoff, "submittedWithContext")
          || !flag(handoff, "started")
          || !flag(handoff, "completed")
          || flag(handoff, "entryWithContext")) {
        continue;
      }
      String scenario = string(handoff, "scenario");
      Map<String, Counts> baseline = counts(report, string(handoff, "handoffId"));
      if (total(baseline, true) == 0) {
        continue;
      }
      JsonObject result = new JsonObject();
      result.addProperty("name", string(handoff, "boundary"));
      result.addProperty("boundary", string(handoff, "boundary"));
      result.addProperty("baselineScenario", scenario);
      result.addProperty("interventionScenario", "");
      JsonArray affected = new JsonArray();
      baseline.forEach(
          (method, count) -> {
            if (count.root > 0) affected.add(method);
          });
      result.add("affectedMethods", affected);
      result.addProperty("affectedMethodCount", affected.size());
      result.addProperty("rootEntries", total(baseline, true));
      result.addProperty("baselineRootEntries", total(baseline, true));
      result.addProperty("baselineContextEntries", total(baseline, false));
      result.addProperty("interventionRootEntries", 0);
      result.addProperty("interventionContextEntries", 0);
      result.add("submissionStack", handoff.getAsJsonArray("submissionStack").deepCopy());
      result.add("recoveredMethods", new JsonArray());
      result.addProperty("recoveredMethodCount", 0);
      result.addProperty("recoveredEntries", 0);
      assess(report, handoff, baseline, result);
      opportunities.add(result);
    }
    opportunities.sort(
        Comparator.comparingInt((JsonObject o) -> rank(string(o, "status")))
            .thenComparing(
                Comparator.comparingLong(
                        (JsonObject o) ->
                            o.get(
                                    string(o, "status").equals("VERIFIED_GAIN")
                                        ? "recoveredMethodCount"
                                        : "affectedMethodCount")
                                .getAsLong())
                    .reversed())
            .thenComparing(
                Comparator.comparingLong((JsonObject o) -> o.get("recoveredEntries").getAsLong())
                    .reversed())
            .thenComparing(o -> string(o, "boundary"))
            .thenComparing(o -> string(o, "baselineScenario")));
    JsonArray output = new JsonArray();
    opportunities.forEach(output::add);
    report.add("opportunities", output);
  }

  private static void assess(
      JsonObject report, JsonObject handoff, Map<String, Counts> baseline, JsonObject result) {
    String scenario = string(handoff, "scenario");
    List<JsonObject> experiments = matching(report, "experiments", "baselineScenario", scenario);
    if (experiments.size() == 1) {
      result.addProperty("name", string(experiments.get(0), "name"));
      result.addProperty(
          "interventionScenario", string(experiments.get(0), "interventionScenario"));
    }
    JsonObject health = report.getAsJsonObject("health");
    if (!flag(report, "finalized")
        || health.get("droppedObservations").getAsLong() != 0
        || (health.has("errors") && !health.getAsJsonArray("errors").isEmpty())) {
      status(result, "INCONCLUSIVE", "The report is incomplete or observations were lost.");
    } else if (matching(report, "handoffs", "scenario", scenario).size() != 1
        || experiments.size() > 1) {
      status(
          result, "INCONCLUSIVE", "Multiple handoffs or experiments make attribution ambiguous.");
    } else if (flag(handoff, "failed")) {
      status(result, "INCONCLUSIVE", "The baseline task failed.");
    } else if (experiments.isEmpty()) {
      status(
          result,
          "CANDIDATE",
          "Observed context loss; a matched propagation experiment is needed.");
    } else {
      String interventionScenario = string(result, "interventionScenario");
      List<JsonObject> controls = matching(report, "handoffs", "scenario", interventionScenario);
      if (scenario.equals(interventionScenario) || controls.size() != 1) {
        status(result, "INCONCLUSIVE", "A distinct, unambiguous intervention handoff is required.");
        return;
      }
      JsonObject control = controls.get(0);
      if (!string(handoff, "boundary").equals(string(control, "boundary"))
          || !flag(control, "submittedWithContext")
          || !flag(control, "started")
          || !flag(control, "completed")
          || flag(control, "failed")
          || !flag(control, "entryWithContext")) {
        status(
            result,
            "INCONCLUSIVE",
            "The intervention must complete successfully with context at the same boundary.");
        return;
      }
      Map<String, Counts> intervention = counts(report, string(control, "handoffId"));
      result.addProperty("interventionRootEntries", total(intervention, true));
      result.addProperty("interventionContextEntries", total(intervention, false));
      if (!baseline.keySet().equals(intervention.keySet())
          || baseline.entrySet().stream()
              .anyMatch(e -> e.getValue().total() != intervention.get(e.getKey()).total())) {
        status(
            result, "INCONCLUSIVE", "Per-method invocation counts differ between the workloads.");
        return;
      }
      JsonArray recovered = new JsonArray();
      JsonArray regressed = new JsonArray();
      long recoveredEntries = 0;
      long regressedEntries = 0;
      for (Map.Entry<String, Counts> entry : baseline.entrySet()) {
        Counts before = entry.getValue();
        Counts after = intervention.get(entry.getKey());
        if (before.root > 0 && after.root == 0 && after.context > 0) {
          recovered.add(entry.getKey());
        }
        recoveredEntries += Math.max(0, before.root - after.root);
        if (after.root > before.root) {
          regressed.add(entry.getKey());
          regressedEntries += after.root - before.root;
        }
      }
      result.add("regressedMethods", regressed);
      result.addProperty("regressedMethodCount", regressed.size());
      result.addProperty("regressedEntries", regressedEntries);
      if (regressedEntries > 0 || recoveredEntries == 0) {
        status(
            result,
            "NO_GAIN",
            regressedEntries > 0
                ? "The matched workload regressed for at least one method; no improvement is claimed."
                : "The matched workload did not recover any root-context entries.");
      } else {
        result.add("recoveredMethods", recovered);
        result.addProperty("recoveredMethodCount", recovered.size());
        result.addProperty("recoveredEntries", recoveredEntries);
        status(
            result,
            "VERIFIED_GAIN",
            "Measured recovery in this matched workload only; opportunities are not additive.");
      }
    }
  }

  private static Map<String, Counts> counts(JsonObject report, String handoffId) {
    Map<String, Counts> counts = new TreeMap<>();
    for (JsonElement item : report.getAsJsonArray("methods")) {
      JsonObject method = item.getAsJsonObject();
      for (JsonElement entry : method.getAsJsonArray("observations")) {
        JsonObject observation = entry.getAsJsonObject();
        if (handoffId.equals(string(observation, "handoffId"))) {
          long count = observation.get("count").getAsLong();
          if (count > 0) {
            boolean root = string(observation, "state").equals("ROOT_CONTEXT");
            counts.merge(
                string(method, "method"),
                new Counts(root ? count : 0, root ? 0 : count),
                (a, b) -> new Counts(a.root + b.root, a.context + b.context));
          }
        }
      }
    }
    return counts;
  }

  private static List<JsonObject> matching(
      JsonObject report, String array, String key, String value) {
    List<JsonObject> matches = new ArrayList<>();
    for (JsonElement element : report.getAsJsonArray(array)) {
      JsonObject object = element.getAsJsonObject();
      if (value.equals(string(object, key))) matches.add(object);
    }
    return matches;
  }

  private static long total(Map<String, Counts> counts, boolean root) {
    return counts.values().stream().mapToLong(c -> root ? c.root : c.context).sum();
  }

  private static String string(JsonObject object, String key) {
    return object.get(key).getAsString();
  }

  private static boolean flag(JsonObject object, String key) {
    return object.has(key) && object.get(key).getAsBoolean();
  }

  private static void status(JsonObject result, String status, String reason) {
    result.addProperty("status", status);
    result.addProperty("reason", reason);
  }

  private static int rank(String status) {
    return switch (status) {
      case "VERIFIED_GAIN" -> 0;
      case "CANDIDATE" -> 1;
      case "INCONCLUSIVE" -> 2;
      default -> 3;
    };
  }

  private record Counts(long root, long context) {
    long total() {
      return root + context;
    }
  }
}
