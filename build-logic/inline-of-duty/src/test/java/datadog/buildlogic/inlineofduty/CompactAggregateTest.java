package datadog.buildlogic.inlineofduty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactAggregateTest {
  @TempDir Path directory;

  @Test
  void missingProducerFactsRemainVisibleAsPartialEvidence() throws Exception {
    Files.writeString(
        directory.resolve("missing.json"),
        """
        {"schema_version":1,"missing":true,"source_prefix":"101/missing",\
        "capture_errors":["No JIT aggregation facts produced by job 101"]}
        """);
    Map<String, Object> report = CompactAggregate.merge(directory, 2, false);
    assertEquals(1, ((List<?>) report.get("capture_errors")).size());
    assertEquals(0, ((Map<?, ?>) report.get("summary")).get("worker_count"));
  }

  @Test
  void mergesMethodStatesAndVetoesRefusalWhenAnotherJobInlinesTheSite() throws Exception {
    Map<String, Object> site =
        Map.of(
            "root", "datadog.Root.work()V",
            "ancestry", List.of(),
            "caller", "datadog.Root.work()V",
            "bci", "7",
            "callee", "datadog.Helper.call()V");
    Map<String, Object> candidate =
        Map.ofEntries(
            Map.entry("root", site.get("root")),
            Map.entry("ancestry", site.get("ancestry")),
            Map.entry("caller", site.get("caller")),
            Map.entry("bci", site.get("bci")),
            Map.entry("callee", site.get("callee")),
            Map.entry("log", "hotspot_pid1.log"),
            Map.entry("classification", "repeated-refusal"),
            Map.entry("eligible_refusal_compilations", 2),
            Map.entry("observed_success_compilations", 0),
            Map.entry("compiler", "c2"),
            Map.entry("compile_kind", "normal"),
            Map.entry("osr_bci", "-1"),
            Map.entry(
                "decisions",
                List.of(Map.of("eligible", true, "outcome", "refusal", "reason", "too big"))));
    writeFacts("one.json", "101/run-a", "refused", List.of(candidate), List.of());
    Map<String, Object> alone = CompactAggregate.merge(directory, 2, false);
    assertEquals(1, ((List<?>) alone.get("actionable_findings")).size());

    writeFacts(
        "two.json", "102/run-b", "inlined", List.of(), List.of(LogAnalyzer.recordedCallSite(site)));
    Map<String, Object> combined = CompactAggregate.merge(directory, 2, false);
    Map<?, ?> summary = (Map<?, ?>) combined.get("summary");
    assertEquals(1, summary.get("total_methods"));
    assertEquals(2, summary.get("worker_count"));
    assertEquals(1L, ((Map<?, ?>) ((Map<?, ?>) summary.get("states")).get("mixed")).get("count"));
    assertEquals(0, ((List<?>) combined.get("actionable_findings")).size());
  }

  private void writeFacts(
      String filename,
      String prefix,
      String state,
      List<Map<String, Object>> candidates,
      List<String> successfulSites)
      throws Exception {
    Map<String, Object> method =
        Map.ofEntries(
            Map.entry("method", "datadog.Helper.call()V"),
            Map.entry("state", state),
            Map.entry("explicit_prohibition", false),
            Map.entry("worker_count", 1),
            Map.entry("refusal_compilations", state.equals("refused") ? 2 : 0),
            Map.entry("refusal_workers", state.equals("refused") ? 1 : 0),
            Map.entry("successful_compilations", state.equals("inlined") ? 1 : 0),
            Map.entry("reasons", state.equals("refused") ? List.of("too big") : List.of()),
            Map.entry(
                "decision_counts",
                Map.of("success", 0, "refusal", 0, "mixed", 0, "unresolved", 0)));
    Map<String, Object> facts =
        Map.ofEntries(
            Map.entry("schema_version", 1),
            Map.entry("source_prefix", prefix),
            Map.entry(
                "policy",
                Map.of("min_compilations", 2, "include_test_code", false, "sample_percent", 10)),
            Map.entry(
                "captures",
                List.of(
                    Map.of("log", "hotspot_pid1.log", "complete", true, "warnings", List.of()))),
            Map.entry("capture_errors", List.of()),
            Map.entry("methods", List.of(method)),
            Map.entry("candidates", candidates),
            Map.entry("successful_sites", successfulSites),
            Map.entry("context_count", 1),
            Map.entry("duplicate_logs_ignored", 0));
    Files.writeString(directory.resolve(filename), LogAnalyzer.json(facts));
  }
}
