package datadog.buildlogic.inlineofduty;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/** Root build integration for optional ordinary-test JIT capture and reporting. */
public final class InlineOfDutyPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    if (project != project.getRootProject()) {
      throw new GradleException("Apply dd-trace-java.inline-of-duty to the root project");
    }
    Provider<Boolean> enabled =
        project
            .getProviders()
            .gradleProperty("inlineOfDuty")
            .map(value -> !"false".equalsIgnoreCase(value))
            .orElse(false);
    Provider<String> externalInput = project.getProviders().gradleProperty("inlineOfDutyInput");
    Provider<Boolean> reportEnabled =
        enabled
            .orElse(false)
            .zip(
                externalInput.map(value -> true).orElse(false),
                (capture, external) -> capture || external);
    Provider<String> pipelineId = project.getProviders().environmentVariable("CI_PIPELINE_ID");
    Provider<String> jobId = project.getProviders().environmentVariable("CI_JOB_ID");
    Provider<String> capturePath =
        pipelineId
            .zip(
                jobId,
                (pipeline, job) -> {
                  if (!pipeline.matches("[0-9]+") || !job.matches("[0-9]+")) {
                    throw new GradleException(
                        "JIT Inlining Report expects numeric GitLab pipeline and job IDs");
                  }
                  return "inline-of-duty/captures/" + pipeline + "/" + job;
                })
            .orElse("inline-of-duty/captures");
    Provider<InlineOfDutyRun> run =
        project
            .getGradle()
            .getSharedServices()
            .registerIfAbsent(
                "inlineOfDutyRun",
                InlineOfDutyRun.class,
                spec -> {
                  spec.getParameters()
                      .getCaptureRoot()
                      .set(project.getLayout().getBuildDirectory().dir(capturePath));
                  spec.getParameters().getPipelineId().set(pipelineId.orElse(""));
                  spec.getParameters().getJobId().set(jobId.orElse(""));
                  spec.getParameters()
                      .getJobName()
                      .set(project.getProviders().environmentVariable("CI_JOB_NAME").orElse(""));
                });
    Provider<Directory> captures =
        project
            .getLayout()
            .dir(externalInput.map(File::new).orElse(run.map(InlineOfDutyRun::getDirectory)));
    TaskProvider<InlineOfDutyReport> report =
        project
            .getTasks()
            .register(
                "inlineOfDutyReport",
                InlineOfDutyReport.class,
                task -> {
                  task.setGroup("verification");
                  task.setDescription(
                      "Reports JIT decisions from this test execution, or from -PinlineOfDutyInput");
                  task.getCaptureDirectory().set(captures);
                  task.getExistingCaptureInput()
                      .convention(externalInput.map(value -> true).orElse(false));
                  task.getCaptureFiles().from(captures.map(directory -> directory.getAsFileTree()));
                  task.getReportDirectory()
                      .set(
                          project
                              .getLayout()
                              .dir(
                                  project
                                      .getProviders()
                                      .gradleProperty("inlineOfDutyOutput")
                                      .map(File::new))
                              .orElse(
                                  project
                                      .getLayout()
                                      .getBuildDirectory()
                                      .dir("reports/inline-of-duty")));
                  task.getPipelineReport()
                      .convention(
                          project
                              .getProviders()
                              .gradleProperty("inlineOfDutyPipelineReport")
                              .map(value -> !"false".equalsIgnoreCase(value))
                              .orElse(false));
                  task.getSourcePipelineId().convention(pipelineId.orElse(""));
                  task.getSourceProjectUrl()
                      .convention(
                          project.getProviders().environmentVariable("CI_PROJECT_URL").orElse(""));
                  task.getMinCompilations()
                      .convention(
                          project
                              .getProviders()
                              .gradleProperty("inlineOfDutyMinCompilations")
                              .map(Integer::parseInt)
                              .orElse(2));
                  task.getTop()
                      .convention(
                          project
                              .getProviders()
                              .gradleProperty("inlineOfDutyTop")
                              .map(Integer::parseInt)
                              .orElse(10));
                  task.getFullEvidence()
                      .convention(
                          project
                              .getProviders()
                              .gradleProperty("inlineOfDutyFullEvidence")
                              .map(Boolean::parseBoolean)
                              .orElse(false));
                  task.getIncludeTestCode()
                      .convention(
                          project
                              .getProviders()
                              .gradleProperty("inlineOfDutyIncludeTestCode")
                              .map(Boolean::parseBoolean)
                              .orElse(false));
                  task.onlyIf(new Enabled(reportEnabled));
                  task.usesService(run);
                });
    project.allprojects(
        candidate -> {
          var tests = candidate.getTasks().withType(Test.class);
          report.configure(task -> task.mustRunAfter(tests));
          tests.configureEach(
              test -> {
                test.getJvmArgumentProviders()
                    .add(new InlineOfDutyArguments(enabled, run, test.getPath()));
                test.getOutputs().upToDateWhen(new Disabled(enabled));
                test.getOutputs()
                    .doNotCacheIf(
                        "JIT Inlining Report requires live worker observations",
                        new Enabled(enabled));
                test.doFirst(new Capture(enabled, run, test.getPath()));
                test.usesService(run);
                test.finalizedBy(report);
              });
        });
  }

  private static final class Enabled implements Spec<Task>, Serializable {
    private final Provider<Boolean> enabled;

    private Enabled(Provider<Boolean> enabled) {
      this.enabled = enabled;
    }

    @Override
    public boolean isSatisfiedBy(Task task) {
      return enabled.get();
    }
  }

  private static final class Disabled implements Spec<Task>, Serializable {
    private final Provider<Boolean> enabled;

    private Disabled(Provider<Boolean> enabled) {
      this.enabled = enabled;
    }

    @Override
    public boolean isSatisfiedBy(Task task) {
      return !enabled.get();
    }
  }

  private static final class Capture implements Action<Task>, Serializable {
    private final Provider<Boolean> enabled;
    private final Provider<InlineOfDutyRun> run;
    private final String taskPath;

    private Capture(Provider<Boolean> enabled, Provider<InlineOfDutyRun> run, String taskPath) {
      this.enabled = enabled;
      this.run = run;
      this.taskPath = taskPath;
    }

    @Override
    public void execute(Task task) {
      if (!enabled.get()) {
        return;
      }
      Test test = (Test) task;
      File directory = run.get().directoryFor(taskPath);
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("schema_version", 1);
      manifest.put("task", taskPath);
      manifest.put("ci_pipeline_id", run.get().getParameters().getPipelineId().get());
      manifest.put("ci_job_id", run.get().getParameters().getJobId().get());
      manifest.put("ci_job_name", run.get().getParameters().getJobName().get());
      manifest.put(
          "java_executable",
          test.getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
      manifest.put(
          "java_runtime_version",
          test.getJavaLauncher().get().getMetadata().getJavaRuntimeVersion());
      manifest.put("java_vendor", test.getJavaLauncher().get().getMetadata().getVendor());
      manifest.put("fork_every", test.getForkEvery());
      manifest.put("max_parallel_forks", test.getMaxParallelForks());
      manifest.put(
          "agent_jars",
          test.getAllJvmArgs().stream()
              .filter(value -> value.startsWith("-javaagent:"))
              .map(value -> value.substring("-javaagent:".length()).split("=", 2)[0])
              .toList());
      try {
        Files.createDirectories(directory.toPath());
        Files.writeString(
            directory.toPath().resolve("capture.json"),
            LogAnalyzer.json(manifest) + "\n",
            StandardCharsets.UTF_8);
      } catch (IOException failure) {
        throw new GradleException("Cannot prepare JIT capture for " + taskPath, failure);
      }
      test.getLogger().lifecycle("JIT Inlining Report capture for {}: {}", taskPath, directory);
    }
  }
}
