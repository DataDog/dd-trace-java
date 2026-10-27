package datadog.buildlogic.inlineofduty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Produces native JVM JSON/HTML reports after the selected test workers have exited. */
@DisableCachingByDefault(because = "JIT diagnostics describe a particular test execution")
public abstract class InlineOfDutyReport extends DefaultTask {
  @Internal
  public abstract DirectoryProperty getCaptureDirectory();

  /** Raw evidence links and capture metadata depend on the selected input location. */
  @Input
  public String getEvidenceDirectoryPath() {
    return getCaptureDirectory().get().getAsFile().getAbsolutePath();
  }

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getCaptureFiles();

  @OutputDirectory
  public abstract DirectoryProperty getReportDirectory();

  @Input
  public abstract Property<Integer> getMinCompilations();

  @Input
  public abstract Property<Integer> getTop();

  @Input
  public abstract Property<Boolean> getFullEvidence();

  @Input
  public abstract Property<Boolean> getIncludeTestCode();

  @Input
  public abstract Property<Boolean> getExistingCaptureInput();

  @Input
  public abstract Property<Boolean> getPipelineReport();

  @Input
  public abstract Property<String> getSourcePipelineId();

  @Input
  public abstract Property<String> getSourceProjectUrl();

  @TaskAction
  public void report() throws IOException {
    if (getMinCompilations().get() < 2) {
      throw new GradleException("inlineOfDutyMinCompilations must be at least 2");
    }
    if (getTop().get() < 1) {
      throw new GradleException("inlineOfDutyTop must be at least 1");
    }
    Path input = getCaptureDirectory().get().getAsFile().toPath();
    if (getExistingCaptureInput().get() && !Files.isDirectory(input)) {
      throw new GradleException("JIT Inlining Report input directory does not exist: " + input);
    }
    Files.createDirectories(input);
    Set<File> inputs = getCaptureFiles().getFiles();
    Set<Path> workerDirectories =
        inputs.stream()
            .filter(file -> file.getName().startsWith("hotspot") && file.getName().endsWith(".log"))
            .map(file -> file.toPath().getParent())
            .collect(Collectors.toSet());
    List<String> captureErrors =
        inputs.stream()
            .filter(file -> file.getName().equals("capture.json"))
            .map(file -> file.toPath().getParent())
            .filter(directory -> !workerDirectories.contains(directory))
            .map(
                directory ->
                    "Missing worker logs for captured test task: " + input.relativize(directory))
            .sorted()
            .toList();
    boolean hasLogs = !workerDirectories.isEmpty();
    Map<String, Object> report =
        hasLogs
            ? LogAnalyzer.analyze(input, getMinCompilations().get(), getIncludeTestCode().get())
            : LogAnalyzer.emptyReport(getMinCompilations().get(), getIncludeTestCode().get());
    if (getPipelineReport().get()) {
      String pipeline = getSourcePipelineId().get();
      String projectUrl = getSourceProjectUrl().get();
      if (!pipeline.matches("[0-9]+") || projectUrl.isBlank()) {
        throw new GradleException("Pipeline reporting requires CI_PIPELINE_ID and CI_PROJECT_URL");
      }
      report.put(
          "pipeline_artifacts",
          Map.of(
              "project_url",
              projectUrl,
              "pipeline_id",
              pipeline,
              "source_capture_prefix",
              "workspace/build/inline-of-duty/captures/" + pipeline));
    }
    report.put("capture_errors", captureErrors);
    report.put("capture_directory", input.toAbsolutePath().toString());
    Path output = getReportDirectory().get().getAsFile().toPath();
    LogAnalyzer.writeReports(report, input, output, getTop().get(), getFullEvidence().get());
    getLogger().lifecycle("JIT Inlining Report: {}", output.resolve("index.html"));
    List<?> captures = (List<?>) report.get("captures");
    boolean incomplete =
        captures.stream()
            .anyMatch(capture -> !Boolean.TRUE.equals(((Map<?, ?>) capture).get("complete")));
    if (!captureErrors.isEmpty()) {
      throw new GradleException(String.join("; ", captureErrors));
    }
    if (incomplete) {
      throw new GradleException(
          "Incomplete JIT capture; partial reports were written to " + output);
    }
  }
}
