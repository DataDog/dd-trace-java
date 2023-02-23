package datadog.trace.instrumentation.gradle;

import java.util.Map;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;

public class GradleBuildListener extends BuildAdapter {

  private static final String TEST_TASK_CLASS_NAME = "org.gradle.api.tasks.testing.Test";

  @Override
  public void settingsEvaluated(Settings settings) {
    // FIXME remove, too little is known at this point
  }

  @Override
  public void projectsLoaded(Gradle gradle) {
    // FIXME remove, too little is known at this point
  }

  @Override
  public void projectsEvaluated(Gradle gradle) {
    Project rootProject = gradle.getRootProject();
    if (containsTestTasks(rootProject)) {
      System.out.println(
          "!!!!!!!!!!!!!!!!!!! PROJECTS EVALUATED: "
              + rootProject.getName()
              + " !!!!!!!!!!!!!!!!!!!");
    }

    // FIXME use isTestTask and JavaForkOptions.class to set up test tasks
  }

  @Override
  public void buildFinished(BuildResult result) {
    Gradle gradle = result.getGradle();
    Project rootProject = gradle.getRootProject();
    if (containsTestTasks(rootProject)) {
      System.out.println(
          "!!!!!!!!!!!!!!!!!!! BUILD FINISHED: " + rootProject.getName() + " !!!!!!!!!!!!!!!!!!!");
    }
  }

  private boolean containsTestTasks(Project project) {
    for (Task task : project.getTasks()) {
      if (isTestTask(task)) {
        return true;
      }
    }
    Map<String, Project> childProjects = project.getChildProjects();
    if (childProjects != null) {
      for (Project childProject : childProjects.values()) {
        if (containsTestTasks(childProject)) {
          return true;
        }
      }
    }
    return false;
  }

  // TODO figure out why Test class cannot be used
  private boolean isTestTask(Task task) {
    Class<?> taskClass = task.getClass();
    while (taskClass != null) {
      if (TEST_TASK_CLASS_NAME.equals(taskClass.getName())) {
        return true;
      }
      taskClass = taskClass.getSuperclass();
    }
    return false;
  }
}
