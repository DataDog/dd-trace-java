package datadog.trace.instrumentation.gradle;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.process.JavaForkOptions;

public class GradleBuildListener extends BuildAdapter {

  private static final String TEST_TASK_CLASS_NAME = "org.gradle.api.tasks.testing.Test";

  private final Map<Gradle, AgentScope.Continuation> buildsInProgress = new ConcurrentHashMap<>();

  @Override
  public void settingsEvaluated(Settings settings) {
    final AgentSpan span =
        startSpan("gradle.test_session"); // FIXME "name": "mocha.test_session", ????

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    AgentScope.Continuation continuation = scope.capture();

    Gradle gradle = settings.getGradle();
    buildsInProgress.put(gradle, continuation);

    GradleDecorator.DECORATE.afterTestSessionStart(span);
  }

  @Override
  public void projectsEvaluated(Gradle gradle) {
    AgentScope.Continuation continuation = buildsInProgress.get(gradle);
    AgentSpan span = continuation.getSpan();
    long sessionId = span.getSpanId(); // FIXME pass to module events?

    Project rootProject = gradle.getRootProject();
    forEveryTestTask(rootProject, this::instrumentTestTask);

    // FIXME remove, not needed
    System.out.println(
        "!!!!!!!!!!!!!!!!!!! PROJECTS EVALUATED: "
            + rootProject.getName()
            + " !!!!!!!!!!!!!!!!!!!");
  }

  @Override
  public void buildFinished(BuildResult result) {
    Gradle gradle = result.getGradle();
    AgentScope.Continuation continuation = buildsInProgress.remove(gradle);

    AgentScope scope = continuation.activate();
    scope.close();

    AgentSpan span = scope.span();
    GradleDecorator.DECORATE.beforeTestSessionFinish(span);
    span.finish();

    // FIXME remove, not needed
    System.out.println(
        "!!!!!!!!!!!!!!!!!!! BUILD FINISHED: "
            + gradle.getRootProject().getName()
            + " !!!!!!!!!!!!!!!!!!!");
  }

  private void forEveryTestTask(Project project, Consumer<Task> action) {
    for (Task task : project.getTasks()) {
      if (isTestTask(task)) {
        action.accept(task);
      }
    }
    Map<String, Project> childProjects = project.getChildProjects();
    if (childProjects != null) {
      for (Project childProject : childProjects.values()) {
        forEveryTestTask(childProject, action);
      }
    }
  }

  // TODO figure out why Test class cannot be used
  private boolean isTestTask(Task task) {
    if (!(task instanceof JavaForkOptions)) {
      return false;
    }
    Class<?> taskClass = task.getClass();
    while (taskClass != null) {
      if (TEST_TASK_CLASS_NAME.equals(taskClass.getName())) {
        return true;
      }
      taskClass = taskClass.getSuperclass();
    }
    return false;
  }

  private void instrumentTestTask(Task task) {
    // FIXME add start/end listeners for test tasks
    // FIXME in listeners, create module span and pass ID to child process
    // FIXME update test framework instrumentations to not create their own module span if ID is
    // provided

    task.doFirst(new TestTaskStartAction());
    task.doLast(new TestTaskFinishAction());

    // FIXME JavaForkOptions.class to set up test tasks
  }

  static final class TestTaskStartAction implements Action<Task> {
    @Override
    public void execute(Task task) {
      System.out.println(
          "!!!!!!!!!!!!!!!!!!! EXECUTION STARTED: " + task.getName() + " !!!!!!!!!!!!!!!!!!!");
    }
  }

  static final class TestTaskFinishAction implements Action<Task> {
    @Override
    public void execute(Task task) {
      System.out.println(
          "!!!!!!!!!!!!!!!!!!! EXECUTION FINISHED: " + task.getName() + " !!!!!!!!!!!!!!!!!!!");
    }
  }
}
