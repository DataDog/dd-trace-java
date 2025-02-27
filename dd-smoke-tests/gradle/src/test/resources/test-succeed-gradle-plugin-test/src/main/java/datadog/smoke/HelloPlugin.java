package datadog.smoke;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class HelloPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getTasks().create("hello", task -> {
      task.doLast(t -> {
        System.out.println("Hello from my plugin!");
      });
    });
  }
}
