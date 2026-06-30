/*
 * Gradle debugging plugin for dd-trace-java builds.
 *
 * Logs the JDK used by each scheduled task to diagnose unexpected Java versions.
 *
 * Usage:
 *   ./gradlew <task> -PddGradleDebug      e.g. ./gradlew assemble -PddGradleDebug
 *
 * Only tasks in the execution graph of the requested command are reported, so run it
 * against a real task (e.g. `build`, `:module:test`); `help` reports almost nothing.
 *
 * Output: build/datadog.gradle-debug.log (one JSON object per task), e.g.
 *   {"task":":dd-trace-api:compileJava", "jdk":"8"}
 *   {"task":":dd-trace-api:test", "jdk":"11"}
 *
 * "jdk":"unknown" means the task type carries no JVM (e.g. lifecycle/aggregate tasks like
 * `classes` or `assemble`, copy/`Sync` tasks); only Java/Groovy/Scala compile, Test, JavaExec,
 * Javadoc and Exec tasks report a version.
 */

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionGraph

val ddGradleDebugEnabled = project.hasProperty("ddGradleDebug")
val logPath = rootProject.layout.buildDirectory.file("datadog.gradle-debug.log")

fun inferJdkFromJavaHome(javaHome: String?): String {
  val effectiveJavaHome = javaHome ?: providers.environmentVariable("JAVA_HOME").orNull ?: error("JAVA_HOME is not set")
  val javaExecutable = File(effectiveJavaHome, "bin/java").absolutePath
  return try {
    val process = ProcessBuilder(javaExecutable, "-version")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val versionLine = output.lines().firstOrNull() ?: ""
    val versionMatch = Regex("version\\s+\"([0-9._]+)\"").find(versionLine)
    versionMatch?.let {
        val version = it.groupValues[1]
        when {
            version.startsWith("1.") -> version.substring(2, 3)
            else -> version.split('.').first()
        }
    } ?: "unknown"
  } catch (e: Exception) {
    "error: ${e.message}"
  }
}

fun getJdkFromCompilerOptions(co: CompileOptions): String? {
  if (co.isFork) {
    val fo = co.forkOptions
    val javaHome = fo.javaHome
    if (javaHome != null) {
      return inferJdkFromJavaHome(javaHome.toString())
    }
  }
  return null
}

fun printJdkForTasks(tasks: Iterable<Task>, logFile: File) {
  tasks.forEach { task ->
    val data = mutableMapOf<String, String>()
    data["task"] = task.path
    if (task is JavaExec) {
      val launcher = task.javaLauncher.get()
      data["jdk"] = launcher.metadata.languageVersion.toString()
    } else if (task is Javadoc) {
      val tool = task.javadocTool.get()
      data["jdk"] = tool.metadata.languageVersion.toString()
    } else if (task is Test) {
      val launcher = task.javaLauncher.get()
      data["jdk"] = launcher.metadata.languageVersion.toString()
    } else if (task is Exec) {
      val javaHome = task.environment.get("JAVA_HOME")?.toString()
      data["jdk"] = inferJdkFromJavaHome(javaHome)
    } else if (task is JavaCompile) {
      val compiler = task.javaCompiler.get()
      data["jdk"] = compiler.metadata.languageVersion.toString()
      val jdkFromJavaHome = getJdkFromCompilerOptions(task.options)
      if (jdkFromJavaHome != null && jdkFromJavaHome != data["jdk"]) {
        data["java_home"] = jdkFromJavaHome
      }
    } else if (task is GroovyCompile) {
      val launcher = task.javaLauncher.get()
      data["jdk"] = launcher.metadata.languageVersion.toString()
      val jdkFromJavaHome = getJdkFromCompilerOptions(task.options)
      if (jdkFromJavaHome != null && jdkFromJavaHome != data["jdk"]) {
        data["java_home"] = jdkFromJavaHome
      }
    } else if (task is ScalaCompile) {
      val launcher = task.javaLauncher.get()
      data["jdk"] = launcher.metadata.languageVersion.toString()
      val jdkFromJavaHome = getJdkFromCompilerOptions(task.options)
      if (jdkFromJavaHome != null && jdkFromJavaHome != data["jdk"]) {
        data["java_home"] = jdkFromJavaHome
      }
    } else {
      data["jdk"] = "unknown"
    }
    val json = data.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "\"$k\":\"$v\"" }
    logFile.appendText("$json\n")
  }
}

if (ddGradleDebugEnabled) {
  logger.lifecycle("datadog.gradle-debug plugin is enabled")
  // Inspect tasks once the execution graph is ready, when scheduled tasks are fully configured.
  gradle.taskGraph.whenReady(Action<TaskExecutionGraph> {
    val logFile = logPath.get().asFile
    logFile.writeText("")
    printJdkForTasks(allTasks, logFile)
  })
}
