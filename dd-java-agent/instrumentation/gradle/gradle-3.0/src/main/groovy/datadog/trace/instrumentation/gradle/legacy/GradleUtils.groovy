package datadog.trace.instrumentation.gradle.legacy

import datadog.trace.api.civisibility.domain.BuildModuleLayout
import datadog.trace.api.civisibility.domain.JavaAgent
import datadog.trace.api.civisibility.domain.SourceSet
import org.gradle.StartParameter
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.internal.jvm.Jvm
import org.gradle.process.JavaForkOptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.nio.file.Paths

abstract class GradleUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(GradleUtils)

  private static final String TEST_TASK_CLASS_NAME = "org.gradle.api.tasks.testing.Test"

  static boolean isTestTask(Task task) {
    if (!(task instanceof JavaForkOptions)) {
      return false
    }
    Class<?> taskClass = task.getClass()
    while (taskClass != null) {
      // class name can contain suffix "_Decorated"
      if (taskClass.getName().startsWith(TEST_TASK_CLASS_NAME)) {
        return true
      }
      taskClass = taskClass.getSuperclass()
    }
    return false
  }

  /**
   * Returns command line used to start the build. We instrument Gradle daemon process, not the
   * client process that is launched from the command line, so the result of this method is an
   * approximation of what the actual command could look like
   */
  static String recreateStartCommand(StartParameter startParameter) {
    StringBuilder command = new StringBuilder("gradle")

    for (String taskName : startParameter.getTaskNames()) {
      command.append(' ').append(taskName)
    }

    for (String excludedTaskName : startParameter.getExcludedTaskNames()) {
      command.append(" -x").append(excludedTaskName)
    }

    for (Map.Entry<String, String> e : startParameter.getProjectProperties().entrySet()) {
      String propertyKey = e.getKey()
      String propertyValue = e.getValue()
      command.append(" -P").append(propertyKey)
      if (propertyValue != null && !propertyValue.isEmpty()) {
        command.append('=').append(propertyValue)
      }
    }

    return command.toString()
  }

  static BuildModuleLayout getModuleLayout(Project project, List<String> sourceSetNames) {
    Collection<SourceSet> sourceSets = new ArrayList<>()
    for (String sourceSetName : sourceSetNames) {
      def sourceSet = project.sourceSets.findByName(sourceSetName)
      if (sourceSet == null) {
        continue
      }

      def srcDirs = sourceSet.allSource.srcDirs
      def destinationDirs = sourceSet.output.files

      SourceSet.Type type = sourceSet.name.toLowerCase().contains("test") ? SourceSet.Type.TEST : SourceSet.Type.CODE
      sourceSets.add(new SourceSet(type, srcDirs, destinationDirs))
    }
    return new BuildModuleLayout(sourceSets)
  }

  static Path getEffectiveExecutable(Task task) {
    if (task.hasProperty('javaLauncher') && task.javaLauncher.isPresent()) {
      try {
        return Paths.get(task.javaLauncher.get().getExecutablePath().toString())
      } catch (Exception e) {
        LOGGER.error("Could not get Java launcher for test task", e)
      }
    }
    def stringPath = task.hasProperty('executable') && task.executable != null
      ? task.executable
      : Jvm.current().getJavaExecutable().getAbsolutePath()
    return stringPath != null ? Paths.get(stringPath) : null
  }

  static List<Path> getClasspath(Task task) {
    if (task.hasProperty("classpath")) {
      try {
        Collection<File> files = task.classpath.getFiles()
        List<Path> paths = new ArrayList<>(files.size())
        for (File file : files) {
          paths.add(file.toPath())
        }
        return paths
      } catch (Exception e) {
        LOGGER.error("Could not get classpath for test task", e)
      }
    }
    return null
  }

  static JavaAgent getJacocoAgent(Task task) {
    def jacocoExtension = task.extensions.findByName("jacoco")
    if (jacocoExtension != null) {
      def jacocoJvmArg = jacocoExtension.asJvmArg // -javaagent:<PATH>/agent.jar=<ARGS>
      String noPrefix = jacocoJvmArg.substring(jacocoJvmArg.indexOf(':') + 1)

      def agentArgsIdx = noPrefix.indexOf('=')
      if (agentArgsIdx < 0) {
        return new JavaAgent(noPrefix, null)
      }

      String agentPath = noPrefix.substring(0, agentArgsIdx)
      String args = noPrefix.substring(agentArgsIdx + 1)
      return new JavaAgent(agentPath, args)
    }
    return null
  }
}
