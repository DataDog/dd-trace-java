package datadog.buildlogic.inlineofduty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises capture through ordinary Gradle Test workers without forcing JIT compilation. */
class InlineOfDutyPluginTest {
  @TempDir Path directory;
  private String buildDirectory = "build";
  private String reportDirectory;

  @Test
  void disabledByDefaultAndExplicitFalsePreserveNormalTestsWithoutCapture() throws Exception {
    singleProject();
    for (String[] arguments :
        List.of(
            new String[] {"test"},
            new String[] {"test", "-PinlineOfDuty=false", "--rerun-tasks"})) {
      BuildResult result = runner(arguments).build();
      assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
      assertEquals(TaskOutcome.SKIPPED, result.task(":inlineOfDutyReport").getOutcome());
      assertTrue(Files.isRegularFile(testXml("")));
      assertTrue(captures().isEmpty(), "Disabled capture must not create worker logs");
      assertFalse(
          Files.exists(
              directory.resolve(buildDirectory + "/reports/inline-of-duty/findings.json")));
    }
  }

  @Test
  void enabledOrdinaryTestCapturesWorkerAndFinalizesReport() throws Exception {
    singleProject();
    BuildResult result = runner("test", "-PinlineOfDuty").build();
    assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertTrue(Files.readString(testXml("")).contains("INLINE_OF_DUTY_WORKER_FLAGS="));
    List<Path> logs = captures();
    assertEquals(1, logs.size());
    assertTrue(Files.readString(logs.get(0)).contains("<hotspot_log"));
    String manifest = Files.readString(logs.get(0).getParent().resolve("capture.json"));
    assertTrue(Pattern.compile("\"max_parallel_forks\"\\s*:\\s*2").matcher(manifest).find());
    assertTrue(Pattern.compile("\"fork_every\"\\s*:\\s*1").matcher(manifest).find());
    assertTrue(manifest.contains("fixture-agent.jar"));
    assertCurrentReport(logs);
  }

  @Test
  void failingTestRetainsJUnitXmlAndFinalizedReport() throws Exception {
    singleProject();
    BuildResult result = runner("test", "-PinlineOfDuty=true", "-PfixtureFail").buildAndFail();
    assertEquals(TaskOutcome.FAILED, result.task(":test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertTrue(Files.readString(testXml("")).contains("<failure"));
    List<Path> logs = captures();
    assertEquals(1, logs.size());
    assertCurrentReport(logs);
  }

  @Test
  void rootReportAggregatesBothSelectedProjects() throws Exception {
    write("settings.gradle", "rootProject.name = 'fixture'\ninclude 'first', 'second'\n");
    write(
        "build.gradle",
        "plugins { id 'dd-trace-java.inline-of-duty' }\nsubprojects {\n" + javaBuild() + "\n}\n");
    testSource("first/");
    testSource("second/");
    BuildResult result = runner(":first:test", ":second:test", "-PinlineOfDuty").build();
    assertEquals(TaskOutcome.SUCCESS, result.task(":first:test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, result.task(":second:test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertEquals(
        1,
        result.getTasks().stream().filter(t -> t.getPath().equals(":inlineOfDutyReport")).count());
    List<Path> firstRun = captures();
    assertEquals(2, firstRun.size());
    assertCurrentReport(firstRun);
    assertTrue(Files.isRegularFile(testXml("first/")));
    assertTrue(Files.isRegularFile(testXml("second/")));

    runner(":first:test", "-PinlineOfDuty", "--rerun-tasks").build();
    List<Path> secondRun = new ArrayList<>(captures());
    secondRun.removeAll(firstRun);
    assertEquals(1, secondRun.size());
    assertCurrentReport(secondRun);
    String report = report();
    for (Path old : firstRun) {
      assertFalse(
          report.contains(old.getFileName().toString()),
          "Earlier or unselected project capture leaked into report");
    }
  }

  @Test
  void skippedTestsCannotResurrectPreviousCaptures() throws Exception {
    singleProject();
    runner("test", "-PinlineOfDuty").build();
    List<Path> previous = captures();
    assertEquals(1, previous.size());

    BuildResult skipped = runner("test", "-PinlineOfDuty", "-PskipFixture").build();
    assertEquals(TaskOutcome.SKIPPED, skipped.task(":test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, skipped.task(":inlineOfDutyReport").getOutcome());
    assertCurrentReport(List.of());
    assertEquals(previous, captures(), "Skipped tests should not start another worker");
    assertFalse(report().contains(previous.get(0).getFileName().toString()));
  }

  @Test
  void missingWorkerLogsProducePartialReportWithoutFailingTests() throws Exception {
    singleProject();
    Files.writeString(
        directory.resolve("build.gradle"),
        """

        tasks.named('test') {
          doLast {
            fileTree(layout.buildDirectory.dir('inline-of-duty/captures')).matching {
              include '**/hotspot*.log'
            }.files.each { it.delete() }
          }
        }
        """,
        java.nio.file.StandardOpenOption.APPEND);
    BuildResult result = runner("test", "-PinlineOfDuty").build();
    assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertTrue(result.getOutput().contains("JIT Inlining Report is partial"));
    assertTrue(report().contains("capture_errors"));
    assertTrue(
        Files.readString(directory.resolve("build/reports/inline-of-duty/index.html"))
            .contains("Missing worker logs for captured test task"));
    assertTrue(
        Files.readString(reportRoot().resolve("summary.md")).contains("results are partial"));
  }

  @Test
  void malformedWorkerLogProducesPartialReportWithoutFailingAggregation() throws Exception {
    singleProject();
    write("existing/run/test/hotspot_pid123.log", "<hotspot_log><task");
    BuildResult result = runner("inlineOfDutyReport", "-PinlineOfDutyInput=existing").build();
    assertEquals(null, result.task(":test"));
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertTrue(result.getOutput().contains("JIT Inlining Report is partial"));
    assertTrue(report().contains("\"complete\":false"));
    assertTrue(
        Files.readString(reportRoot().resolve("summary.md")).contains("results are partial"));
  }

  @Test
  void configurationCacheReuseGetsFreshCaptureIdentity() throws Exception {
    singleProject();
    runner("test", "-PinlineOfDuty", "--configuration-cache", "--rerun-tasks").build();
    List<Path> first = captures();
    assertEquals(1, first.size());
    BuildResult second =
        runner("test", "-PinlineOfDuty", "--configuration-cache", "--rerun-tasks").build();
    assertTrue(second.getOutput().contains("Reusing configuration cache"));
    List<Path> fresh = new ArrayList<>(captures());
    fresh.removeAll(first);
    assertEquals(
        1, fresh.size(), "Each invocation needs a fresh capture identity, including cache reuse");
    assertCurrentReport(fresh);
    assertFalse(report().contains(first.get(0).getFileName().toString()));
  }

  @Test
  void exportOptionsBoundEvidenceAndCanExplicitlyRestoreFullDetail() throws Exception {
    singleProject();
    try (var source = getClass().getResourceAsStream("/repeated.xml")) {
      write(
          "existing/hotspot_pid42.log",
          new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    runner("inlineOfDutyReport", "-PinlineOfDutyInput=existing", "-PinlineOfDutyTop=1").build();
    String details = Files.readString(reportRoot().resolve("details.html"));
    assertEquals(1, details.split("<tr id=", -1).length - 1);
    assertTrue(report().contains("\"contexts_total\":3"));
    assertTrue(report().contains("\"total_methods\":2"));
    runner(
            "inlineOfDutyReport",
            "-PinlineOfDutyInput=existing",
            "-PinlineOfDutyTop=1",
            "-PinlineOfDutyFullEvidence=true")
        .build();
    details = Files.readString(reportRoot().resolve("details.html"));
    assertEquals(3, details.split("<tr id=", -1).length - 1);
    assertTrue(
        runner("inlineOfDutyReport", "-PinlineOfDutyInput=existing", "-PinlineOfDutyTop=0")
            .buildAndFail()
            .getOutput()
            .contains("inlineOfDutyTop must be at least 1"));
  }

  @Test
  void standaloneAnalysisDoesNotScheduleTestsOrCaptureWorkers() throws Exception {
    singleProject();
    write(
        "existing/hotspot_pid123.log",
        """
        <?xml version='1.0' encoding='UTF-8'?>
        <hotspot_log version='160 1' process='123' time_ms='0'>
          <hotspot_log_done stamp='1.0'/>
        </hotspot_log>
        """);
    BuildResult result = runner("inlineOfDutyReport", "-PinlineOfDutyInput=existing").build();
    assertEquals(null, result.task(":test"));
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertTrue(captures().isEmpty());
    assertCurrentReport(List.of(directory.resolve("existing/hotspot_pid123.log")));

    write(
        "copied/hotspot_pid123.log",
        Files.readString(directory.resolve("existing/hotspot_pid123.log")));
    BuildResult copied = runner("inlineOfDutyReport", "-PinlineOfDutyInput=copied").build();
    assertEquals(TaskOutcome.SUCCESS, copied.task(":inlineOfDutyReport").getOutcome());
    assertTrue(report().contains(directory.resolve("copied").toString()));
    assertFalse(report().contains(directory.resolve("existing").toString()));
  }

  @Test
  void explicitMissingInputFailsClearlyWithoutSchedulingTests() throws Exception {
    singleProject();
    BuildResult result =
        runner("inlineOfDutyReport", "-PinlineOfDutyInput=missing-captures").buildAndFail();
    assertEquals(null, result.task(":test"));
    assertEquals(TaskOutcome.FAILED, result.task(":inlineOfDutyReport").getOutcome());
    assertTrue(result.getOutput().contains("JIT Inlining Report input directory does not exist"));
    assertTrue(captures().isEmpty());
  }

  @Test
  void captureAndReportsFollowRelocatedRootBuildDirectory() throws Exception {
    singleProject();
    buildDirectory = "workspace/build";
    write(
        "build.gradle",
        "plugins { id 'dd-trace-java.inline-of-duty' }\n"
            + "layout.buildDirectory = file('workspace/build')\n"
            + javaBuild());
    BuildResult result = runner("test", "-PinlineOfDuty").build();
    assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    List<Path> logs = captures();
    assertEquals(1, logs.size());
    assertCurrentReport(logs);
    assertTrue(Files.isRegularFile(testXml("")));
    assertFalse(Files.exists(directory.resolve("build/inline-of-duty/captures")));
    assertFalse(Files.exists(directory.resolve("build/reports/inline-of-duty")));
  }

  @Test
  void ciJobArtifactsMergeWithinSelectedPipelineWithoutIncludingForeignCaptures() throws Exception {
    singleProject();
    String pipeline = "123";
    String firstJob = "91001";
    String secondJob = "91002";
    Path captureRoot = directory.resolve("build/inline-of-duty/captures");
    runner(
            Map.of("CI_PIPELINE_ID", pipeline, "CI_JOB_ID", firstJob),
            "test",
            "-PinlineOfDuty",
            "--rerun-tasks")
        .build();
    List<Path> first = captures();
    assertEquals(1, first.size());
    assertTrue(first.get(0).startsWith(captureRoot.resolve(pipeline).resolve(firstJob)));
    assertCurrentReport(first);

    BuildResult failedProducer =
        runner(
                Map.of("CI_PIPELINE_ID", pipeline, "CI_JOB_ID", secondJob),
                "test",
                "-PinlineOfDuty",
                "-PfixtureFail",
                "--rerun-tasks")
            .buildAndFail();
    assertEquals(TaskOutcome.FAILED, failedProducer.task(":test").getOutcome());
    assertEquals(TaskOutcome.SUCCESS, failedProducer.task(":inlineOfDutyReport").getOutcome());
    List<Path> second = new ArrayList<>(captures());
    second.removeAll(first);
    assertEquals(1, second.size());
    assertTrue(second.get(0).startsWith(captureRoot.resolve(pipeline).resolve(secondJob)));
    assertCurrentReport(second);
    assertFalse(report().contains(first.get(0).getFileName().toString()));

    Path staging = directory.resolve("staging/captures");
    for (String job : List.of(firstJob, secondJob)) {
      Path artifactSubtree = Path.of(pipeline, job);
      copyTree(captureRoot.resolve(artifactSubtree), staging.resolve(artifactSubtree));
    }
    write("staging/captures/124/91003/run/test/hotspot_pid999.log", hotspotLog(999, 9));

    reportDirectory = "pipeline-report";
    BuildResult aggregate =
        runner(
                Map.of(
                    "CI_PIPELINE_ID",
                    pipeline,
                    "CI_JOB_ID",
                    "999",
                    "CI_PROJECT_URL",
                    "https://gitlab.example/team/repo"),
                "inlineOfDutyReport",
                "-PinlineOfDutyInput=staging/captures/" + pipeline,
                "-PinlineOfDutyOutput=" + reportDirectory,
                "-PinlineOfDutyPipelineReport")
            .build();
    assertEquals(null, aggregate.task(":test"));
    assertEquals(TaskOutcome.SUCCESS, aggregate.task(":inlineOfDutyReport").getOutcome());
    assertCurrentReport(List.of(first.get(0), second.get(0)));
    String combined = report();
    String evidence = Files.readString(reportRoot().resolve("details.html"));
    for (var producer : Map.of(firstJob, first.get(0), secondJob, second.get(0)).entrySet()) {
      String captureLabel =
          captureRoot
              .resolve(pipeline)
              .relativize(producer.getValue())
              .toString()
              .replace('\\', '/');
      assertTrue(
          evidence.contains(
              "https://gitlab.example/team/repo/-/jobs/"
                  + producer.getKey()
                  + "/artifacts/file/workspace/build/inline-of-duty/captures/"
                  + pipeline
                  + "/"
                  + captureLabel));
    }
    assertFalse(
        evidence.contains("/-/jobs/999/"),
        "Evidence must link the producer, not the aggregate job");
    assertTrue(Pattern.compile("\"log\"\\s*:\\s*\"" + firstJob + "/").matcher(combined).find());
    assertTrue(Pattern.compile("\"log\"\\s*:\\s*\"" + secondJob + "/").matcher(combined).find());
    assertFalse(combined.contains("91003"));
    assertFalse(combined.contains("hotspot_pid999.log"));
    assertEquals(2, captures().size(), "Standalone aggregation must not launch more workers");
    try (Stream<Path> outputs = Files.walk(reportRoot())) {
      assertTrue(
          outputs.noneMatch(path -> path.getFileName().toString().endsWith(".log")),
          "Pipeline reports must link producer artifacts without copying raw worker logs");
    }
  }

  @Test
  void samePidFilenameInDifferentJobArtifactsRemainsTwoDistinctWorkers() throws Exception {
    singleProject();
    write("staging/captures/73001/91001/run/test/hotspot_pid777.log", hotspotLog(777, 1));
    write("staging/captures/73001/91002/run/test/hotspot_pid777.log", hotspotLog(777, 2));
    BuildResult result =
        runner("inlineOfDutyReport", "-PinlineOfDutyInput=staging/captures/73001").build();
    assertEquals(null, result.task(":test"));
    assertEquals(TaskOutcome.SUCCESS, result.task(":inlineOfDutyReport").getOutcome());
    assertCurrentReport(
        List.of(
            directory.resolve("staging/captures/73001/91001/run/test/hotspot_pid777.log"),
            directory.resolve("staging/captures/73001/91002/run/test/hotspot_pid777.log")));
    String combined = report();
    assertTrue(combined.contains("91001/run/test/hotspot_pid777.log"));
    assertTrue(combined.contains("91002/run/test/hotspot_pid777.log"));
    assertTrue(captures().isEmpty());
  }

  private static String hotspotLog(int process, int stamp) {
    return """
        <?xml version='1.0' encoding='UTF-8'?>
        <hotspot_log version='160 1' process='%d' time_ms='%d'>
          <hotspot_log_done stamp='%d.0'/>
        </hotspot_log>
        """
        .formatted(process, stamp, stamp);
  }

  private static void copyTree(Path source, Path destination) throws IOException {
    try (Stream<Path> entries = Files.walk(source)) {
      for (Path entry : entries.toList()) {
        Path target = destination.resolve(source.relativize(entry));
        if (Files.isDirectory(entry)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(entry, target);
        }
      }
    }
  }

  private void singleProject() throws Exception {
    write("settings.gradle", "rootProject.name = 'fixture'\n");
    write("build.gradle", "plugins { id 'dd-trace-java.inline-of-duty' }\n" + javaBuild());
    testSource("");
  }

  // Resolve JUnit jars from this suite instead of downloading dependencies in nested builds.
  private String javaBuild() throws Exception {
    Set<String> jars = new LinkedHashSet<>();
    for (String name :
        List.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.engine.JupiterTestEngine",
            "org.junit.platform.engine.TestEngine",
            "org.junit.platform.commons.JUnitException",
            "org.junit.platform.launcher.Launcher",
            "org.opentest4j.AssertionFailedError")) {
      Path jar =
          Path.of(Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI());
      jars.add("'" + jar.toString().replace("\\", "\\\\").replace("'", "\\'") + "'");
    }
    return """
        apply plugin: 'java'
        dependencies { testImplementation files(%s) }
        def agentJar = layout.buildDirectory.file('libs/fixture-agent.jar').get().asFile
        def fixtureAgent = tasks.register('fixtureAgent', Jar) {
          archiveFileName = 'fixture-agent.jar'
          from sourceSets.main.output
          manifest { attributes('Premain-Class': 'fixture.MarkerAgent') }
        }
        def skipFixture = providers.gradleProperty('skipFixture').map { true }.orElse(false)
        tasks.withType(Test).configureEach {
          useJUnitPlatform()
          maxParallelForks = 2
          forkEvery = 1
          dependsOn fixtureAgent
          jvmArgs '-javaagent:' + agentJar.absolutePath
          jvmArgs '-Dfixture.marker=retained'
          systemProperty 'fixture.expectJit', providers.gradleProperty('inlineOfDuty').map { it != 'false' }.getOrElse(false)
          systemProperty 'fixture.fail', providers.gradleProperty('fixtureFail').isPresent()
          onlyIf { !skipFixture.get() }
        }
        """
        .formatted(String.join(", ", jars));
  }

  private void testSource(String prefix) throws IOException {
    write(
        prefix + "src/main/java/fixture/MarkerAgent.java",
        """
        package fixture;
        public class MarkerAgent {
          public static void premain(String arguments) {
            System.setProperty("fixture.agent", "retained");
          }
        }
        """);
    write(
        prefix + "src/test/java/fixture/OrdinaryTest.java",
        """
        package fixture;
        import java.lang.management.ManagementFactory;
        import java.util.List;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertFalse;
        public class OrdinaryTest {
          @Test void ordinaryWork() {
            List<String> flags = ManagementFactory.getRuntimeMXBean().getInputArguments();
            System.out.println("INLINE_OF_DUTY_WORKER_FLAGS=" + flags);
            assertEquals("retained", System.getProperty("fixture.marker"));
            assertEquals("retained", System.getProperty("fixture.agent"));
            assertEquals(Boolean.getBoolean("fixture.expectJit"), flags.contains("-XX:+LogCompilation"));
            assertFalse(Boolean.getBoolean("fixture.fail"), "Intentional fixture failure");
          }
        }
        """);
  }

  private GradleRunner runner(String... arguments) {
    return runner(Map.of(), arguments);
  }

  private GradleRunner runner(Map<String, String> extraEnvironment, String... arguments) {
    List<String> args = new ArrayList<>(Arrays.asList(arguments));
    args.addAll(List.of("--stacktrace", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx512m"));
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("JAVA_HOME", System.getProperty("java.home"));
    environment.remove("JAVA_TOOL_OPTIONS");
    environment.remove("JDK_JAVA_OPTIONS");
    environment.remove("_JAVA_OPTIONS");
    environment.remove("CI_PIPELINE_ID");
    environment.remove("CI_JOB_ID");
    environment.putAll(extraEnvironment);
    return GradleRunner.create()
        .withProjectDir(directory.toFile())
        .withPluginClasspath()
        .withArguments(args)
        .withEnvironment(environment);
  }

  private List<Path> captures() throws IOException {
    Path root = directory.resolve(buildDirectory + "/inline-of-duty/captures");
    if (!Files.exists(root)) return List.of();
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(p -> p.getFileName().toString().matches("hotspot_pid.*\\.log"))
          .sorted()
          .toList();
    }
  }

  private Path reportRoot() {
    return directory.resolve(
        reportDirectory == null ? buildDirectory + "/reports/inline-of-duty" : reportDirectory);
  }

  private String report() throws IOException {
    return Files.readString(reportRoot().resolve("findings.json"));
  }

  private void assertCurrentReport(List<Path> logs) throws IOException {
    assertTrue(Files.isRegularFile(reportRoot().resolve("index.html")));
    assertTrue(Files.isRegularFile(reportRoot().resolve("details.html")));
    String json = report();
    var count = Pattern.compile("\"worker_count\"\\s*:\\s*(\\d+)").matcher(json);
    assertTrue(count.find(), "Report must expose worker_count");
    assertEquals(logs.size(), Integer.parseInt(count.group(1)), json);
    for (Path log : logs)
      assertTrue(
          json.contains(log.getFileName().toString()), "Report must identify capture " + log);
  }

  private Path testXml(String prefix) {
    return directory.resolve(
        prefix + buildDirectory + "/test-results/test/TEST-fixture.OrdinaryTest.xml");
  }

  private void write(String path, String contents) throws IOException {
    Path target = directory.resolve(path);
    Files.createDirectories(target.getParent());
    Files.writeString(target, contents);
  }
}
