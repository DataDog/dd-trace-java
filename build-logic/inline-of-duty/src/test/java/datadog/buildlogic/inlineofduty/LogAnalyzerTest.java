package datadog.buildlogic.inlineofduty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogAnalyzerTest {
  @TempDir Path directory;
  private Path log;
  private String fixture;

  @BeforeEach
  void prepare() throws IOException {
    try (var source = getClass().getResourceAsStream("/repeated.xml")) {
      fixture = new String(source.readAllBytes(), StandardCharsets.UTF_8);
    }
    log = directory.resolve("hotspot_pid42.log");
    Files.writeString(log, fixture);
  }

  private Map<String, Object> analyze(String text) throws IOException {
    Files.writeString(log, text);
    return LogAnalyzer.analyze(directory, 2, false);
  }

  @Test
  void resolvesTaskLocalDescriptorsAndNestedAncestry() throws IOException {
    var findings = maps(analyze(fixture).get("findings"));
    assertEquals(3, findings.size());
    assertEquals(Set.of("repeated-refusal"), classifications(findings));
    assertTrue(
        findings.stream().allMatch(f -> number(f.get("eligible_refusal_compilations")) == 2));
    var helpers =
        findings.stream().filter(f -> f.get("callee").toString().endsWith("help()V")).toList();
    assertEquals(2, helpers.size());
    assertNotEquals(helpers.get(0).get("ancestry"), helpers.get(1).get("ancestry"));
    assertEquals(
        Set.of("5", "15"),
        helpers.stream()
            .map(f -> maps(f.get("ancestry")).get(0).get("bci").toString())
            .collect(Collectors.toSet()));
    assertTrue(findings.stream().anyMatch(f -> f.get("callee").toString().endsWith("help(I)V")));
  }

  @Test
  void identicalCallerAndCalleeRemainDistinctAtDifferentInlinePaths() throws IOException {
    var findings = maps(analyze(fixture.replace("name='second'", "name='first'")).get("findings"));
    var helpers =
        findings.stream().filter(f -> f.get("callee").toString().endsWith("help()V")).toList();
    assertEquals(2, helpers.size());
    assertEquals(helpers.get(0).get("caller"), helpers.get(1).get("caller"));
    assertEquals(helpers.get(0).get("bci"), helpers.get(1).get("bci"));
    assertNotEquals(helpers.get(0).get("ancestry"), helpers.get(1).get("ancestry"));
  }

  @Test
  void symbolIdsAreNotGlobalAcrossTasks() throws IOException {
    int start = fixture.indexOf("<task compile_id='2'");
    String text =
        fixture.substring(0, start)
            + fixture
                .substring(start)
                .replace("name='datadog.trace.Helper'", "name='datadog.trace.Other'");
    var findings = maps(analyze(text).get("findings"));
    assertEquals(6, findings.size());
    assertEquals(Set.of("single-refusal"), classifications(findings));
  }

  @Test
  void c1DoesNotEstablishC2Persistence() throws IOException {
    var findings =
        maps(
            analyze(fixture.replace("compile_id='2' compiler='c2'", "compile_id='2' compiler='c1'"))
                .get("findings"));
    assertEquals(Set.of("single-refusal", "ineligible-refusal"), classifications(findings));
    assertEquals(
        Set.of("c1", "c2"),
        findings.stream().map(f -> f.get("compiler").toString()).collect(Collectors.toSet()));
  }

  @Test
  void osrAndNormalCompilationsRemainSeparate() throws IOException {
    String text =
        fixture
            .replace(
                "compile_id='2' compiler='c2'",
                "compile_id='2' compiler='c2' compile_kind='osr' osr_bci='9'")
            .replace("<task compile_id='2'", "<task compile_id='2' compile_kind='osr' osr_bci='9'");
    var findings = maps(analyze(text).get("findings"));
    assertEquals(6, findings.size());
    assertEquals(Set.of("single-refusal"), classifications(findings));
  }

  @Test
  void duplicateCompileIdsAndMessagesDoNotInflateCounts() throws IOException {
    var report = analyze(fixture.replace("compile_id='2'", "compile_id='1'"));
    var findings = maps(report.get("findings"));
    assertEquals(Set.of("single-refusal"), classifications(findings));
    assertTrue(findings.stream().allMatch(f -> maps(f.get("decisions")).size() == 1));
    for (var method : maps(object(report.get("summary")).get("methods"))) {
      assertEquals(1, number(method.get("refusal_compilations")));
      assertTrue(number(object(method.get("decision_counts")).get("refusal")) <= 2);
    }
    String doubled =
        fixture.replace(
            "<inline_fail reason='hot method too big'/>",
            "<inline_fail reason='hot method too big'/><inline_fail reason='hot method too big'/>");
    String originalSummary = LogAnalyzer.json(object(analyze(fixture).get("summary")));
    assertEquals(originalSummary, LogAnalyzer.json(object(analyze(doubled).get("summary"))));
  }

  @Test
  void onlyCompletedInstalledCompilationsSupportOutcomes() throws IOException {
    for (String text :
        List.of(
            fixture.replace("success='1'", "success='0'"),
            fixture
                .replace("<nmethod compile_id='1' compiler='c2'/>", "")
                .replace("<nmethod compile_id='2' compiler='c2'/>", ""))) {
      var report = analyze(text);
      assertEquals(Set.of("ineligible-refusal"), classifications(maps(report.get("findings"))));
      assertEquals(2, stateCount(report, "uncertain"));
      assertEquals(0, stateCount(report, "refused"));
    }
  }

  @Test
  void truncationDisablesPersistenceForWholeLogAndRetainsPartialTask() throws IOException {
    var report = analyze(fixture.substring(0, fixture.lastIndexOf("<task_done")));
    assertFalse((boolean) maps(report.get("captures")).get(0).get("complete"));
    assertFalse(classifications(maps(report.get("findings"))).contains("repeated-refusal"));
    var decisions =
        maps(report.get("findings")).stream()
            .flatMap(f -> maps(f.get("decisions")).stream())
            .toList();
    assertEquals(
        Set.of("1", "2"),
        decisions.stream().map(d -> d.get("compile_id").toString()).collect(Collectors.toSet()));
    assertTrue(
        decisions.stream().anyMatch(d -> list(d.get("uncertainty")).contains("truncated-task")));
    assertEquals(2, stateCount(report, "uncertain"));
  }

  @Test
  void unrelatedXmlAndDtdAreIncompleteAndNeverResolveExternalEntities() throws IOException {
    var unrelated = analyze("<html><body>wrong root</body></html>");
    assertFalse((boolean) maps(unrelated.get("captures")).get(0).get("complete"));
    Path secret = directory.resolve("secret.txt");
    Files.writeString(secret, "private-value-must-not-be-read");
    String xml =
        "<!DOCTYPE hotspot_log [<!ENTITY leak SYSTEM '"
            + secret.toUri()
            + "'>]><hotspot_log><vm_version><name>&leak;</name></vm_version></hotspot_log>";
    var report = analyze(xml);
    assertFalse((boolean) maps(report.get("captures")).get(0).get("complete"));
    assertFalse(LogAnalyzer.json(report).contains("private-value-must-not-be-read"));
  }

  @Test
  void successesDefeatRepeatedRefusalsEvenWithLateInlineUncertainty() throws IOException {
    String task =
        firstTask()
            .replace("compile_id='1'", "compile_id='3'")
            .replace("<inline_fail", "<inline_success")
            .replace("<task_done", "<late_inline method='1253'/><task_done");
    String text =
        fixture
            .replace(
                "<compilation_log>", "<nmethod compile_id='3' compiler='c2'/><compilation_log>")
            .replace("</compilation_log>", task + "</compilation_log>");
    var report = analyze(text);
    assertEquals(Set.of("mixed"), classifications(maps(report.get("findings"))));
    assertTrue(list(report.get("actionable_findings")).isEmpty());
    assertEquals(2, stateCount(report, "mixed"));
  }

  @Test
  void intrinsicAndLateTasksAreUncertainForPersistenceButExplicitBudgetRefusalsRemainKnown()
      throws IOException {
    for (String marker : List.of("<late_inline method='1253'/>", "<intrinsic id='_hashCode'/>")) {
      var report = analyze(fixture.replace("<task_done", marker + "<task_done"));
      assertEquals(Set.of("uncertain"), classifications(maps(report.get("findings"))));
      assertTrue(list(report.get("actionable_findings")).isEmpty());
      assertEquals(2, stateCount(report, "refused"));
      assertEquals(0, stateCount(report, "uncertain"));
    }
  }

  @Test
  void nativeAndDeferredDecisionsDoNotBecomeNegativeClaims() throws IOException {
    for (String reason :
        List.of("native method", "unresolved signature", "late inline (hot)", "intrinsic")) {
      var report =
          analyze(
              fixture.replace("hot method too big", reason).replace("inlining too deep", reason));
      assertEquals(Set.of("unknown"), classifications(maps(report.get("findings"))));
      assertEquals(2, stateCount(report, "uncertain"));
    }
  }

  @Test
  void noCrossLogPersistenceAndCopiedBytesAreIgnored() throws IOException {
    String single = fixture.replace("compile_id='2'", "compile_id='1'");
    Files.writeString(log, single);
    Files.writeString(
        directory.resolve("hotspot_other.log"),
        single.replace("<hotspot_log>", "<hotspot_log process='43'>"));
    var report = LogAnalyzer.analyze(directory, 2, false);
    assertEquals(2, number(object(report.get("summary")).get("worker_count")));
    assertEquals(Set.of("single-refusal"), classifications(maps(report.get("findings"))));
    assertTrue(list(report.get("actionable_findings")).isEmpty());
    Files.writeString(directory.resolve("hotspot_copy.log"), single);
    report = LogAnalyzer.analyze(directory, 2, false);
    assertEquals(2, list(report.get("captures")).size());
    assertEquals(1, list(report.get("duplicates")).size());
  }

  @Test
  void successInAnotherWorkerMakesDatasetMixedWithoutClaimingEquivalentRuns() throws IOException {
    Files.writeString(
        directory.resolve("hotspot_other.log"), fixture.replace("<inline_fail", "<inline_success"));
    var report = LogAnalyzer.analyze(directory, 2, false);
    assertEquals(2, stateCount(report, "mixed"));
    assertEquals(0, stateCount(report, "refused"));
    assertEquals(0, list(object(report.get("summary")).get("prohibitions")).size());
  }

  @Test
  void methodRecurrenceAcrossDifferentSitesDoesNotPromoteAnySite() throws IOException {
    int second = fixture.indexOf("<task compile_id='2'");
    String text =
        fixture.substring(0, second)
            + fixture
                .substring(second)
                .replace("bci='7'", "bci='8'")
                .replace("bci='25'", "bci='26'");
    var report = analyze(text);
    assertEquals(2, stateCount(report, "refused"));
    assertTrue(
        maps(object(report.get("summary")).get("methods")).stream()
            .allMatch(method -> number(method.get("refusal_compilations")) == 2));
    assertEquals(Set.of("single-refusal"), classifications(maps(report.get("findings"))));
    assertTrue(list(report.get("actionable_findings")).isEmpty());
  }

  @Test
  void onlySpecificRepeatedCompletedC2ContextsArePromoted() throws IOException {
    var report = analyze(fixture);
    var actionable = maps(report.get("actionable_findings"));
    assertEquals(3, actionable.size());
    for (var finding : actionable) {
      assertEquals("repeated-refusal", finding.get("classification"));
      assertEquals("c2", finding.get("compiler"));
      assertEquals(2, number(finding.get("eligible_refusal_compilations")));
      assertEquals(0, number(finding.get("observed_success_compilations")));
      assertEquals("Persistently refused in this workload", finding.get("actionable_label"));
      assertTrue(finding.get("context_id").toString().startsWith("context-"));
      assertTrue(maps(report.get("findings")).contains(finding));
    }
    assertTrue(list(LogAnalyzer.analyze(directory, 3, false).get("actionable_findings")).isEmpty());
    var incomplete = analyze(fixture.replace("success='1'", "success='0'"));
    assertTrue(list(incomplete.get("actionable_findings")).isEmpty());
  }

  @Test
  void successAtAnotherCallerDoesNotHideAQualifiedRefusalAtThisSite() throws IOException {
    var report =
        analyze(
            fixture.replace(
                "<inline_fail reason='inlining too deep'/>",
                "<inline_success reason='inline (hot)'/>"));
    assertEquals(1, stateCount(report, "mixed"));
    assertEquals(1, stateCount(report, "refused"));
    var actionable = maps(report.get("actionable_findings"));
    assertEquals(2, actionable.size());
    assertTrue(actionable.stream().anyMatch(f -> f.get("caller").toString().contains(".first(")));
    assertFalse(actionable.stream().anyMatch(f -> f.get("caller").toString().contains(".second(")));
    assertTrue(
        actionable.stream().anyMatch(f -> f.get("callee").toString().endsWith("help()V")),
        "A mixed method may have a specific consistently refused call site");
  }

  @Test
  void matchingSuccessInAnotherWorkerOrTierVetoesPromotionWithoutPoolingFailures()
      throws IOException {
    for (String compiler : List.of("c1", "c2")) {
      Files.writeString(
          directory.resolve("hotspot_other.log"),
          fixture
              .replace("compiler='c2'", "compiler='" + compiler + "'")
              .replace("<inline_fail", "<inline_success"));
      var report = LogAnalyzer.analyze(directory, 2, false);
      assertEquals(2, stateCount(report, "mixed"));
      assertTrue(list(report.get("actionable_findings")).isEmpty());
    }
  }

  @Test
  void singleExplicitPolicyProhibitionDoesNotBypassPersistenceThreshold() throws IOException {
    var report =
        analyze(
            fixture
                .replace("compile_id='2'", "compile_id='1'")
                .replace("hot method too big", "disallowed by CompileCommand")
                .replace("inlining too deep", "disallowed by CompileCommand"));
    assertEquals(2, list(object(report.get("summary")).get("prohibitions")).size());
    assertTrue(list(report.get("actionable_findings")).isEmpty());
  }

  @Test
  void virtualDispatchRecurrenceIsDiagnosticWhileOnlyQualifiedSitesAppearByDefault()
      throws IOException {
    int second = fixture.indexOf("<task compile_id='2'");
    String text =
        fixture.substring(0, second)
            + fixture
                .substring(second)
                .replace("bci='7'", "bci='8'")
                .replace("bci='25'", "bci='26'");
    text =
        text.replace("hot method too big", "virtual call")
            .replace("inlining too deep", "no static binding");
    var report = analyze(text);
    assertEquals(2, stateCount(report, "refused"));
    assertTrue(list(report.get("actionable_findings")).isEmpty());
    var summary = object(report.get("summary"));
    var methods = maps(summary.get("methods"));
    assertTrue(
        methods.stream()
            .allMatch(m -> number(object(m.get("decision_counts")).get("refusal")) > 0));
    assertTrue(
        maps(report.get("findings")).stream()
            .flatMap(f -> maps(f.get("decisions")).stream())
            .allMatch(d -> d.get("reason_category").equals("unresolved-virtual-dispatch")));
    Path output = directory.resolve("overview");
    LogAnalyzer.writeReports(report, directory, output);
    String page = Files.readString(output.resolve("index.html"));
    assertTrue(page.contains("No call sites meet the persistence criteria in this workload."));
    assertFalse(
        page.substring(0, page.indexOf("<details><summary>Methods with refusals only"))
            .contains("Helper.help"));
    assertTrue(
        page.contains("No inlining observed: 4 refusal observations across captured contexts."));
    assertTrue(page.contains("Unresolved virtual dispatch"));
    assertTrue(page.contains("not independent proofs or a measure of performance impact"));

    var qualified =
        analyze(
            fixture
                .replace("hot method too big", "virtual call")
                .replace("inlining too deep", "no static binding"));
    assertEquals(3, list(qualified.get("actionable_findings")).size());
    LogAnalyzer.writeReports(qualified, directory, output);
    page = Files.readString(output.resolve("index.html"));
    String details = Files.readString(output.resolve("details.html"));
    assertTrue(page.contains("Work.run@5 → Parent.first@7 → Helper.help"));
    assertTrue(page.contains("Persistently refused in this workload"));
    assertTrue(page.contains("Completed C2 refusals"));
    for (var finding : maps(qualified.get("actionable_findings"))) {
      assertTrue(page.contains("href=\"details.html#" + finding.get("context_id") + "\""));
      assertTrue(details.contains("id=\"" + finding.get("context_id") + "\""));
    }
  }

  @Test
  void sameContextSuccessAndRefusalCountAsOneMixedCompilationOutcome() throws IOException {
    var report =
        analyze(
            fixture.replace(
                "<inline_fail reason='hot method too big'/>",
                "<inline_fail reason='hot method too big'/><inline_success reason='inline (hot)'/>"));
    assertEquals(2, stateCount(report, "mixed"));
    for (var method : maps(object(report.get("summary")).get("methods"))) {
      assertTrue(number(object(method.get("decision_counts")).get("mixed")) > 0);
    }
  }

  @Test
  void onlyExactExplicitPolicyMessagesBecomeProhibitions() throws IOException {
    for (String reason :
        List.of("don&apos;t inline by annotation", "disallowed by CompileCommand")) {
      var report =
          analyze(
              fixture.replace("hot method too big", reason).replace("inlining too deep", reason));
      assertEquals(2, list(object(report.get("summary")).get("prohibitions")).size());
    }
    assertEquals(0, list(object(analyze(fixture).get("summary")).get("prohibitions")).size());
  }

  @Test
  void incompleteDatasetPreventsMethodProhibition() throws IOException {
    String policy =
        fixture
            .replace("hot method too big", "disallowed by CompileCommand")
            .replace("inlining too deep", "disallowed by CompileCommand");
    Files.writeString(log, policy);
    Files.writeString(
        directory.resolve("hotspot_other.log"), policy.substring(0, policy.length() - 20));
    var report = LogAnalyzer.analyze(directory, 2, false);
    assertEquals(0, list(object(report.get("summary")).get("prohibitions")).size());
    assertEquals(2, stateCount(report, "refused"));
  }

  @Test
  void defaultExclusionsAreCalleeBasedAndCanBeDisabled() throws IOException {
    for (String text :
        List.of(
            fixture.replace("name='help'", "name='$jacocoInit'"),
            fixture.replace(
                "name='datadog.trace.Helper'", "name='datadog.trace.agent.test.Helper'"),
            fixture.replace("name='datadog.trace.Helper'", "name='datadog.trace.test.Helper'"))) {
      Files.writeString(log, text);
      assertEquals(
          0,
          number(
              object(LogAnalyzer.analyze(directory, 2, false).get("summary"))
                  .get("total_methods")));
      assertEquals(
          2,
          number(
              object(LogAnalyzer.analyze(directory, 2, true).get("summary")).get("total_methods")));
    }
  }

  @Test
  void thresholdAndHistoryAreNumericAndRetainRawEvidence() throws IOException {
    var findings = maps(LogAnalyzer.analyze(directory, 3, false).get("findings"));
    assertEquals(Set.of("below-threshold-refusal"), classifications(findings));
    String text =
        fixture
            .replace("compile_id='1'", "compile_id='10'")
            .replace("<task compile_id='10'", "<task compile_id='10' stamp='10.0'")
            .replace("<task compile_id='2'", "<task compile_id='2' stamp='2.0'")
            .replace("<nmethod compile_id='2'", "<nmethod compile_id='2' stamp='2.1'");
    for (var finding : maps(analyze(text).get("findings"))) {
      var decisions = maps(finding.get("decisions"));
      assertEquals(List.of("2", "10"), decisions.stream().map(d -> d.get("compile_id")).toList());
      assertEquals("2.0", decisions.get(0).get("task_stamp"));
      assertEquals(List.of("2.1"), decisions.get(0).get("installed_stamps"));
      assertTrue(
          Set.of("size-limit", "depth-limit").contains(decisions.get(0).get("reason_category")));
    }
  }

  @Test
  void compactExportBoundsRowsAndSamplesWithoutChangingAnalysisOrTotals() throws IOException {
    var report = analyze(fixture);
    String original = LogAnalyzer.json(report);
    Path output = directory.resolve("compact");
    LogAnalyzer.writeReports(report, directory, output, 1, false);
    String json = Files.readString(output.resolve("findings.json"));
    String page = Files.readString(output.resolve("index.html"));
    String details = Files.readString(output.resolve("details.html"));
    assertTrue(json.contains("\"actionable_contexts_total\":3"));
    assertTrue(json.contains("\"contexts_total\":3"));
    String markdown = Files.readString(output.resolve("summary.md"));
    assertTrue(markdown.contains("2 observed methods across 1 test JVMs"));
    assertTrue(markdown.contains("| 🔴 Refused only | 2 | 100.0% |"));
    assertTrue(markdown.contains("3 call contexts persistently refused"));
    assertTrue(markdown.contains("<!-- inline-of-duty -->"));
    assertTrue(markdown.contains("not coverage or performance"));
    assertTrue(json.contains("\"decisions_total\":2"));
    assertTrue(json.contains("\"total_methods\":2"));
    assertFalse(json.contains("\"methods\":"));
    assertFalse(json.contains("\"vm\":"));
    assertEquals(1, details.split("<tr id=", -1).length - 1);
    assertEquals(1, details.split("<li>Compilation ", -1).length - 1);
    assertTrue(page.contains("Showing up to 1 call contexts"));
    assertTrue(page.contains("showing 1 of 2 methods"));
    assertTrue(details.contains("Export includes 1 of 3 contexts"));
    assertEquals(original, LogAnalyzer.json(report), "Export must not truncate the analysis");
    LogAnalyzer.writeReports(report, directory, output, 1, true);
    String full = Files.readString(output.resolve("details.html"));
    assertEquals(3, full.split("<tr id=", -1).length - 1);
    assertEquals(6, full.split("<li>Compilation ", -1).length - 1);
    assertTrue(Files.readString(output.resolve("findings.json")).contains("\"methods\":"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LogAnalyzer.writeReports(report, directory, output, 0, false));
  }

  @Test
  void htmlIsEscapedCompactAndDeterministic() throws IOException {
    var report =
        analyze(
            fixture.replace(
                "hot method too big", "&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;"));
    Path output = directory.resolve("report");
    LogAnalyzer.writeReports(report, directory, output);
    String details = Files.readString(output.resolve("details.html"));
    assertTrue(details.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;"));
    assertFalse(details.contains("<script>alert("));
    assertTrue(details.contains("href=\"../hotspot_pid42.log\""));
    String index = Files.readString(output.resolve("index.html"));
    assertTrue(index.contains("JIT Inlining Report"));
    assertTrue(index.contains("2 observed methods"));
    assertTrue(index.contains("class=\"card inlined\""));
    assertFalse(index.contains("<th>Root / compiler</th>"));
    List<byte[]> first = new ArrayList<>();
    for (String name : List.of("index.html", "details.html", "findings.json"))
      first.add(Files.readAllBytes(output.resolve(name)));
    LogAnalyzer.writeReports(LogAnalyzer.analyze(directory, 2, false), directory, output);
    int i = 0;
    for (String name : List.of("index.html", "details.html", "findings.json"))
      assertEquals(
          new String(first.get(i++), StandardCharsets.UTF_8),
          Files.readString(output.resolve(name)));
  }

  @Test
  void vmMetadataIsPreservedAndEmptyTaskReportIsFresh() throws IOException {
    var capture = maps(analyze(fixture).get("captures")).get(0);
    assertEquals("25", object(object(capture.get("vm")).get("vm_version")).get("release"));
    Map<String, Object> configured = LogAnalyzer.emptyReport(3, true);
    assertEquals(3, object(configured.get("policy")).get("min_compilations"));
    assertEquals(true, object(configured.get("policy")).get("include_test_code"));
    Map<String, Object> empty = LogAnalyzer.emptyReport();
    assertEquals(0, number(object(empty.get("summary")).get("total_methods")));
    assertEquals(0, list(empty.get("captures")).size());
    assertEquals(0, number(object(empty.get("summary")).get("worker_count")));
    Path emptyDir = directory.resolve("empty");
    Files.createDirectory(emptyDir);
    assertThrows(IOException.class, () -> LogAnalyzer.analyze(emptyDir, 2, false));
    assertThrows(IllegalArgumentException.class, () -> LogAnalyzer.analyze(directory, 1, false));
  }

  @Test
  void largeDocumentsWithPredefinedXmlEscapesRemainValid() throws IOException {
    String text =
        fixture.replace(
            "<compilation_log>",
            "<vm_arguments><args>"
                + "&apos;".repeat(110000)
                + "</args></vm_arguments><compilation_log>");
    var report = analyze(text);
    assertTrue((boolean) maps(report.get("captures")).get(0).get("complete"));
    assertEquals(2, stateCount(report, "refused"));
  }

  @Test
  void jsonEscapesControlsAndSortsMapKeys() {
    assertEquals(
        "{\"a\":\"\\n\\t\\\"\\\\\\u0001\",\"z\":1}",
        LogAnalyzer.json(Map.of("z", 1, "a", "\n\t\"\\\u0001")));
  }

  @Test
  void pipelineReportLinksEachWorkerToItsProducingJobsArtifact() throws IOException {
    Files.delete(log);
    Path first = directory.resolve("101/execution-a/task&suite/hotspot_pid42.log");
    Path second = directory.resolve("202/execution-b/task&suite/hotspot_pid42.log");
    Files.createDirectories(first.getParent());
    Files.createDirectories(second.getParent());
    Files.writeString(first, fixture);
    Files.writeString(second, fixture.replace("<hotspot_log>", "<hotspot_log process='43'>"));
    var report = LogAnalyzer.analyze(directory, 2, false);
    report.put("pipeline_artifacts", pipelineMetadata());
    Path output = directory.resolve("aggregate-report");
    LogAnalyzer.writeReports(report, directory, output);
    String page = Files.readString(output.resolve("details.html"));
    String prefix = "https://gitlab.example/group/project&amp;tools/-/jobs/";
    assertTrue(
        page.contains(
            "href=\""
                + prefix
                + "101/artifacts/file/workspace/build/inline-of-duty/captures/123/101/execution-a/task&amp;suite/hotspot_pid42.log\""));
    assertTrue(
        page.contains(
            "href=\""
                + prefix
                + "202/artifacts/file/workspace/build/inline-of-duty/captures/123/202/execution-b/task&amp;suite/hotspot_pid42.log\""));
    assertFalse(page.contains("/-/jobs/42/"));
    String overview = Files.readString(output.resolve("index.html"));
    assertTrue(overview.contains("Pipeline 123 · 2 captured test jobs."));
    assertTrue(overview.contains("jobs without artifacts are not represented"));
    String firstJson = Files.readString(output.resolve("findings.json"));
    LogAnalyzer.writeReports(report, directory, output);
    assertEquals(page, Files.readString(output.resolve("details.html")));
    assertEquals(firstJson, Files.readString(output.resolve("findings.json")));
  }

  @Test
  void pipelineArtifactUrlsEncodePathsAndRejectUnsafeMetadata() throws IOException {
    Files.delete(log);
    Path capture = directory.resolve("101/run/task #?%/hotspot_pid42.log");
    Files.createDirectories(capture.getParent());
    Files.writeString(capture, fixture);
    var report = LogAnalyzer.analyze(directory, 2, false);
    report.put("pipeline_artifacts", pipelineMetadata());
    Path output = directory.resolve("report");
    LogAnalyzer.writeReports(report, directory, output);
    assertTrue(
        Files.readString(output.resolve("details.html"))
            .contains("task%20%23%3F%25/hotspot_pid42.log"));
    for (String url :
        List.of(
            "javascript:alert(1)",
            "http://gitlab.example/group/project",
            "https://user@gitlab.example/group/project",
            "https://gitlab.example/group/project?redirect=evil",
            "https://gitlab.example/group/../project")) {
      var metadata = new java.util.TreeMap<>(pipelineMetadata());
      metadata.put("project_url", url);
      report.put("pipeline_artifacts", metadata);
      assertThrows(
          IllegalArgumentException.class,
          () -> LogAnalyzer.writeReports(report, directory, output));
    }
    for (String prefix :
        List.of(
            "../captures/123",
            "/workspace/build/inline-of-duty/captures/123",
            "workspace/build/inline-of-duty/captures/456")) {
      var metadata = new java.util.TreeMap<>(pipelineMetadata());
      metadata.put("source_capture_prefix", prefix);
      report.put("pipeline_artifacts", metadata);
      assertThrows(
          IllegalArgumentException.class,
          () -> LogAnalyzer.writeReports(report, directory, output));
    }
    report.put("pipeline_artifacts", pipelineMetadata());
    maps(report.get("captures")).get(0).put("log", "not-a-job/run/hotspot_pid42.log");
    assertThrows(
        IllegalArgumentException.class, () -> LogAnalyzer.writeReports(report, directory, output));
  }

  private static Map<String, Object> pipelineMetadata() {
    return Map.of(
        "project_url",
        "https://gitlab.example/group/project&tools",
        "pipeline_id",
        "123",
        "source_capture_prefix",
        "workspace/build/inline-of-duty/captures/123");
  }

  private String firstTask() {
    return fixture.substring(
        fixture.indexOf("<task compile_id='1'"), fixture.indexOf("<task compile_id='2'"));
  }

  private static int stateCount(Map<String, Object> report, String state) {
    return number(
        object(object(object(report.get("summary")).get("states")).get(state)).get("count"));
  }

  private static Set<String> classifications(List<Map<String, Object>> findings) {
    return findings.stream()
        .map(f -> f.get("classification").toString())
        .collect(Collectors.toSet());
  }

  private static int number(Object value) {
    return ((Number) value).intValue();
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
