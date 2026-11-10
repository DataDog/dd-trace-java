package datadog.buildlogic.inlineofduty;

import groovy.json.JsonSlurper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Transfers method states and qualified call contexts without copying raw JIT logs. */
final class CompactAggregate {
  private static final List<String> STATES = List.of("inlined", "mixed", "refused", "uncertain");

  private CompactAggregate() {}

  static void writeFacts(Map<String, Object> report, Path output, String jobId, String runId)
      throws IOException {
    if (!jobId.matches("[0-9]+") || !runId.matches("[A-Za-z0-9-]+")) {
      throw new IOException("Invalid JIT capture job or execution identity");
    }
    List<Map<String, Object>> captures = new ArrayList<>();
    for (Map<String, Object> capture : maps(report.get("captures"))) {
      captures.add(
          map(
              "log", capture.get("log"),
              "complete", capture.get("complete"),
              "warnings", capture.get("warnings"),
              "sha256", capture.get("sha256")));
    }
    List<Map<String, Object>> candidates = new ArrayList<>();
    for (Map<String, Object> candidate : maps(report.get("actionable_findings"))) {
      Map<String, Object> copy = new TreeMap<>(candidate);
      List<Map<String, Object>> decisions = maps(candidate.get("decisions"));
      copy.put("decisions_total", decisions.size());
      copy.put(
          "decisions",
          decisions.stream()
              .filter(
                  d -> Boolean.TRUE.equals(d.get("eligible")) && "refusal".equals(d.get("outcome")))
              .limit(1)
              .toList());
      candidates.add(copy);
    }
    Set<String> successfulSites = new TreeSet<>();
    for (Map<String, Object> finding : maps(report.get("findings"))) {
      if (number(finding.get("observed_success_compilations")) > 0) {
        successfulSites.add(LogAnalyzer.recordedCallSite(finding));
      }
    }
    Map<String, Object> facts =
        map(
            "schema_version",
            1,
            "source_prefix",
            jobId + "/" + runId,
            "policy",
            report.get("policy"),
            "captures",
            captures,
            "capture_errors",
            report.getOrDefault("capture_errors", List.of()),
            "methods",
            object(report.get("summary")).get("methods"),
            "candidates",
            candidates,
            "successful_sites",
            successfulSites,
            "context_count",
            maps(report.get("findings")).size(),
            "duplicate_logs_ignored",
            maps(report.get("duplicates")).size());
    Files.writeString(output, LogAnalyzer.json(facts) + "\n", StandardCharsets.UTF_8);
  }

  static Map<String, Object> merge(Path input, int minCompilations, boolean includeTestCode)
      throws IOException {
    List<Path> files;
    try (Stream<Path> paths = Files.list(input)) {
      files =
          paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
    }
    if (files.isEmpty()) throw new IOException("No JIT aggregation facts found in " + input);
    Map<String, Map<String, Object>> methods = new TreeMap<>();
    List<Map<String, Object>> captures = new ArrayList<>();
    List<Map<String, Object>> candidates = new ArrayList<>();
    List<String> captureErrors = new ArrayList<>();
    Set<String> successfulSites = new HashSet<>();
    Map<String, Object> policy = null;
    long contextCount = 0;
    long duplicates = 0;
    boolean allComplete = true;
    for (Path file : files) {
      Object parsed = new JsonSlurper().parse(file.toFile(), "UTF-8");
      if (!(parsed instanceof Map<?, ?>))
        throw new IOException("Invalid aggregation facts: " + file);
      Map<String, Object> facts = object(parsed);
      if (number(facts.get("schema_version")) != 1)
        throw new IOException("Unsupported aggregation facts: " + file);
      String prefix = String.valueOf(facts.get("source_prefix"));
      if (!prefix.matches("[0-9]+/[A-Za-z0-9-]+"))
        throw new IOException("Invalid aggregation source prefix: " + file);
      if (Boolean.TRUE.equals(facts.get("missing"))) {
        allComplete = false;
        for (Object error : list(facts.get("capture_errors"))) {
          captureErrors.add(prefix + ": " + error);
        }
        continue;
      }
      Map<String, Object> nextPolicy = object(facts.get("policy"));
      if (number(nextPolicy.get("min_compilations")) != minCompilations
          || !Boolean.valueOf(includeTestCode).equals(nextPolicy.get("include_test_code"))) {
        throw new IOException("Aggregation facts use a different analysis policy: " + file);
      }
      if (policy == null) policy = nextPolicy;
      else if (!Objects.equals(policy.get("sample_percent"), nextPolicy.get("sample_percent")))
        throw new IOException("Aggregation facts use different sampling rates: " + file);
      else if (!Objects.equals(policy.get("capture_scope"), nextPolicy.get("capture_scope")))
        throw new IOException("Aggregation facts use different capture scopes: " + file);
      for (Map<String, Object> capture : maps(facts.get("captures"))) {
        Map<String, Object> copy = new TreeMap<>(capture);
        copy.put("log", prefix + "/" + capture.get("log"));
        captures.add(copy);
        allComplete &= Boolean.TRUE.equals(capture.get("complete"));
      }
      for (Object error : list(facts.get("capture_errors"))) {
        captureErrors.add(prefix + ": " + error);
      }
      for (Map<String, Object> method : maps(facts.get("methods"))) {
        methods.merge(
            String.valueOf(method.get("method")),
            new TreeMap<>(method),
            CompactAggregate::joinMethod);
      }
      for (Map<String, Object> candidate : maps(facts.get("candidates"))) {
        Map<String, Object> copy = new TreeMap<>(candidate);
        copy.put("log", prefix + "/" + candidate.get("log"));
        candidates.add(copy);
      }
      for (Object site : list(facts.get("successful_sites")))
        successfulSites.add(String.valueOf(site));
      contextCount += number(facts.get("context_count"));
      duplicates += number(facts.get("duplicate_logs_ignored"));
    }
    if (policy == null) {
      policy = object(LogAnalyzer.emptyReport(minCompilations, includeTestCode).get("policy"));
    }
    List<Map<String, Object>> mergedMethods = new ArrayList<>(methods.values());
    for (Map<String, Object> method : mergedMethods) {
      method.put(
          "explicit_prohibition",
          allComplete && Boolean.TRUE.equals(method.get("explicit_prohibition")));
    }
    List<Map<String, Object>> actionable =
        candidates.stream()
            .filter(candidate -> !successfulSites.contains(LogAnalyzer.recordedCallSite(candidate)))
            .sorted(
                Comparator.<Map<String, Object>>comparingLong(
                        candidate -> number(candidate.get("eligible_refusal_compilations")))
                    .reversed()
                    .thenComparing(candidate -> String.valueOf(candidate.get("log")))
                    .thenComparing(candidate -> String.valueOf(candidate.get("root")))
                    .thenComparing(candidate -> String.valueOf(candidate.get("caller"))))
            .toList();
    for (int i = 0; i < actionable.size(); i++) {
      actionable.get(i).put("context_id", "context-" + i);
    }
    List<Map<String, Object>> refused =
        new ArrayList<>(
            mergedMethods.stream()
                .filter(method -> "refused".equals(method.get("state")))
                .toList());
    refused.sort(
        Comparator.<Map<String, Object>, Boolean>comparing(
                method -> Boolean.TRUE.equals(method.get("explicit_prohibition")))
            .reversed()
            .thenComparing(
                Comparator.<Map<String, Object>>comparingLong(
                        method -> number(method.get("refusal_workers")))
                    .reversed())
            .thenComparing(
                Comparator.<Map<String, Object>>comparingLong(
                        method -> number(method.get("refusal_compilations")))
                    .reversed())
            .thenComparing(method -> String.valueOf(method.get("method"))));
    Map<String, Object> states = new TreeMap<>();
    for (String state : STATES) {
      long count =
          mergedMethods.stream().filter(method -> state.equals(method.get("state"))).count();
      states.put(
          state,
          map(
              "count",
              count,
              "percent",
              mergedMethods.isEmpty()
                  ? 0.0
                  : Math.round(1000.0 * count / mergedMethods.size()) / 10.0));
    }
    Map<String, Object> summary =
        map(
            "unit",
            "Distinct observed method signatures, not coverage or call frequency",
            "aggregation",
            "Descriptive union; workloads and JVM configurations are not equivalent experiments",
            "total_methods",
            mergedMethods.size(),
            "worker_count",
            captures.size(),
            "duplicate_logs_ignored",
            duplicates,
            "states",
            states,
            "methods",
            mergedMethods,
            "prohibitions",
            mergedMethods.stream()
                .filter(method -> Boolean.TRUE.equals(method.get("explicit_prohibition")))
                .toList(),
            "other_refusals",
            refused);
    return map(
        "schema_version", 1,
        "policy", policy,
        "captures", captures,
        "duplicates", List.of(),
        "findings", actionable,
        "actionable_findings", actionable,
        "context_count", contextCount,
        "capture_errors", captureErrors,
        "summary", summary);
  }

  private static Map<String, Object> joinMethod(
      Map<String, Object> first, Map<String, Object> second) {
    boolean success = hasSuccess(first) || hasSuccess(second);
    boolean refusal = hasRefusal(first) || hasRefusal(second);
    first.put(
        "state", success ? (refusal ? "mixed" : "inlined") : (refusal ? "refused" : "uncertain"));
    first.put(
        "explicit_prohibition",
        Boolean.TRUE.equals(first.get("explicit_prohibition"))
            && Boolean.TRUE.equals(second.get("explicit_prohibition")));
    for (String key :
        List.of(
            "worker_count", "refusal_compilations", "refusal_workers", "successful_compilations")) {
      first.put(key, number(first.get(key)) + number(second.get(key)));
    }
    Set<String> reasons = new TreeSet<>();
    for (Object reason : list(first.get("reasons"))) reasons.add(String.valueOf(reason));
    for (Object reason : list(second.get("reasons"))) reasons.add(String.valueOf(reason));
    first.put("reasons", new ArrayList<>(reasons));
    Map<String, Object> counts = object(first.get("decision_counts"));
    Map<String, Object> more = object(second.get("decision_counts"));
    for (String key : List.of("success", "refusal", "mixed", "unresolved")) {
      counts.put(key, number(counts.get(key)) + number(more.get(key)));
    }
    return first;
  }

  private static boolean hasSuccess(Map<String, Object> method) {
    return "inlined".equals(method.get("state")) || "mixed".equals(method.get("state"));
  }

  private static boolean hasRefusal(Map<String, Object> method) {
    return "refused".equals(method.get("state")) || "mixed".equals(method.get("state"));
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  private static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new TreeMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
