package datadog.gradle.plugin.observer;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Explicit developer artifact: never included in the published distribution. */
@DisableCachingByDefault(because = "Experimental offline transformation of the locally built agent")
public abstract class ObserverAgentTask extends DefaultTask {
  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getStockAgent();

  @OutputFile
  public abstract RegularFileProperty getObserverAgent();

  @TaskAction
  public void rewrite() throws Exception {
    new ObserverAgentRewriter()
        .rewrite(getStockAgent().get().getAsFile(), getObserverAgent().get().getAsFile());
  }
}
