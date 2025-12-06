/*
 * Gradle debugging plugin for dd-trace-java builds.
 */

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

fun printJdkForProjectTasks(project: Project, logFile: File) {
  project.tasks.forEach { task ->
    val data = mutableMapOf<String, String>()
    data["task"] = task.path.toString()
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

class DebugBuildListener : org.gradle.BuildListener {
  override fun settingsEvaluated(settings: Settings) = Unit

  override fun projectsLoaded(gradle: Gradle) = Unit

  override fun buildFinished(result: BuildResult) = Unit

  override fun projectsEvaluated(gradle: Gradle) {
    val logFile = logPath.get().asFile
    logFile.writeText("")
    gradle.rootProject.allprojects.forEach { project ->
      printJdkForProjectTasks(project, logFile)
    }
  }
}

if (ddGradleDebugEnabled) {
  logger.lifecycle("datadog.gradle-debug plugin is enabled")
  gradle.addListener(DebugBuildListener())
}
