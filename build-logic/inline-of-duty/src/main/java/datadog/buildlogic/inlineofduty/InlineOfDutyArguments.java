package datadog.buildlogic.inlineofduty;

import java.util.List;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

/** Adds diagnostic flags without modifying normal compiler policy or existing agents. */
public final class InlineOfDutyArguments implements CommandLineArgumentProvider {
  private final Provider<Boolean> enabled;
  private final Provider<InlineOfDutyRun> run;
  private final String taskPath;

  public InlineOfDutyArguments(
      Provider<Boolean> enabled, Provider<InlineOfDutyRun> run, String taskPath) {
    this.enabled = enabled;
    this.run = run;
    this.taskPath = taskPath;
  }

  @Input
  public Provider<Boolean> getEnabled() {
    return enabled;
  }

  @ServiceReference("inlineOfDutyRun")
  public Provider<InlineOfDutyRun> getRun() {
    return run;
  }

  @Input
  public String getTaskPath() {
    return taskPath;
  }

  @Override
  public Iterable<String> asArguments() {
    if (!enabled.get()) {
      return List.of();
    }
    return List.of(
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+LogCompilation",
        "-XX:LogFile=" + run.get().directoryFor(taskPath).getAbsolutePath() + "/hotspot_%p.log");
  }
}
