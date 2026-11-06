package datadog.buildlogic.inlineofduty;

import java.io.File;
import java.util.UUID;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/** Owns the capture identity for one execution, including configuration-cache replays. */
public abstract class InlineOfDutyRun implements BuildService<InlineOfDutyRun.Parameters> {
  public interface Parameters extends BuildServiceParameters {
    DirectoryProperty getCaptureRoot();

    Property<String> getPipelineId();

    Property<String> getJobId();

    Property<String> getJobName();
  }

  private final String identity = UUID.randomUUID().toString();

  public File getDirectory() {
    return new File(getParameters().getCaptureRoot().get().getAsFile(), identity);
  }

  public File directoryFor(String taskPath) {
    return new File(getDirectory(), taskPath.substring(1).replace(':', '/'));
  }
}
