import org.gradle.api.Plugin;
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult

import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap

/**
 * Records per-task timing and outputs them as JUnit XML reports.
 * Subprojects are presented as test suites and tasks as test cases.
 */
abstract class TaskTimingPlugin implements Plugin<Project> {

  @Inject
  abstract BuildEventsListenerRegistry getEventsListenerRegistry()

  static File REPORTS_DIR

  @Override
  void apply(Project project) {
    REPORTS_DIR = new File(project.rootProject.buildDir, "gradle-project-tests")

    Provider<TaskTimingListener> serviceProvider =
      project.getGradle().getSharedServices().registerIfAbsent(
        "taskTiming", TaskTimingListener.class, spec -> { })

    getEventsListenerRegistry().onTaskCompletion(serviceProvider)
  }

  static class TaskTimingListener implements BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {

    private final Map<String, ProjectState> timings = new ConcurrentHashMap<>()

    @Override
    BuildServiceParameters.None getParameters() {
      return null
    }

    @Override
    void onFinish(final FinishEvent event) {
      if (!(event instanceof TaskFinishEvent)) {
        return
      }
      final result = event.getResult()
      if (result instanceof TaskSkippedResult) {
        return
      }

      final descriptor = event.getDescriptor()
      final taskPath = descriptor.getTaskPath()
      final projectPath = taskPath.substring(0, taskPath.lastIndexOf(':'))
      final failed = result instanceof TaskFailureResult
      final millis = result.getEndTime() - result.getStartTime()
      timings.compute(projectPath, (_, prev) -> {
        final project = prev ?: new ProjectState(projectPath)
        project.add(new TaskState(taskPath, failed, millis))
        return project
      })
    }

    @Override
    void close() {
      generateResultsXML()
    }

    private void generateResultsXML() {
      REPORTS_DIR.mkdirs()

      for (project in timings.values()) {
        final reportFile = new File(REPORTS_DIR, "${project.project}.xml")

        final totalTime = project.tasks.collect { it.millis }.sum()
        final totalFailures = project.tasks.count { it.failed }

        final testCasesString = project.tasks.collect { state ->
          """|  <testcase name="${state.task}" file="${project.project}" classnane="${project.project}" time="${state.millis / 1000f}">"
             |  </testcase>""".stripMargin()
        }.join("\n")

        final text = """|<?xml version="1.0" encoding="UTF-8"?>
                     |<testsuite id="${project.project}" name="${project.project}" tests="${project.tasks.size()}" time="${totalTime / 1000f}" failures="${totalFailures}">
                     |${testCasesString}
                     |</testsuite>\n""".stripMargin()
        reportFile.text = text
      }
    }
  }

  private static class ProjectState {
    String project
    List<TaskState> tasks

    ProjectState(final String project) {
      this.project = project
      tasks = new ArrayList<>()
    }

    void add(final TaskState task) {
      tasks.add(task)
    }

    boolean isFailed() {
      tasks.any { it.failed }
    }

    long millis() {
      tasks.collect { it.millis }.sum() as long
    }
  }

  private static class TaskState {
    String task
    boolean failed
    long millis

    TaskState(final String task, final boolean failed, final long millis) {
      this.task = task
      this.failed = failed
      this.millis = millis
    }
  }
}
