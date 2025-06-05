package datadog.smoke;

import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

public class HelloPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getTasks().create("hello", HelloTask.class);
  }

  public static class HelloTask extends DefaultTask {
    @TaskAction
    public void execute() {
      System.out.println("Hello from my plugin!");
    }
  }
}
