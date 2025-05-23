/*
 * Gradle debugging plugin for dd-trace-java builds.
 */


val ddGradleDebugEnabled = project.hasProperty("ddGradleDebug")
val logPath = "${rootProject.projectDir}/build/datadog.gradle-debug.log"

fun inferJdkFromJavaHome(javaHome: String?): String {
  val effectiveJavaHome = javaHome ?: System.getenv("JAVA_HOME")
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

fun printJdkForProjectTasks(project: Project) {
    project.tasks.forEach { task ->
        val data = mutableMapOf<String, String>()
        data["task"] = task.path.toString()
        if (task is JavaCompile) {
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
        } else if (task.javaClass.name.contains("KotlinCompile")) {
          try {
            val kc = task.javaClass.getDeclaredMethod("getKotlinOptions").invoke(task)
            val jvmTarget = kc.javaClass.getDeclaredMethod("getJvmTarget").invoke(kc)
            val get = jvmTarget.javaClass.getDeclaredMethod("get").invoke(jvmTarget)
            data["jdk"] = get.toString()
          } catch (e: NoSuchMethodException) {

          }
        } else if (task is ScalaCompile) {
          val launcher = task.javaLauncher.get()
          data["jdk"] = launcher.metadata.languageVersion.toString()
          val jdkFromJavaHome = getJdkFromCompilerOptions(task.options)
          if (jdkFromJavaHome != null && jdkFromJavaHome != data["jdk"]) {
            data["java_home"] = jdkFromJavaHome
          }
        } else if (task is JavaExec) {
          val launcher = task.javaLauncher.get()
          data["jdk"] = launcher.metadata.languageVersion.toString()
        } else if (task is Javadoc) {
          val tool = task.javadocTool.get()
          data["jdk"] = tool.metadata.languageVersion.toString()
        } else if (task is Test) {
          val launcher = task.javaLauncher.get()
          data["jdk"] = launcher.metadata.languageVersion.toString()
        } else if (task is Exec) {
          val java_home = task.environment.get("JAVA_HOME")?.toString()
          data["jdk"] = inferJdkFromJavaHome(java_home)
        } else {
          data["jdk"] = "unknown"
        }
        val json = data.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "\"$k\":\"$v\"" }
        File(logPath).appendText("$json\n")
    }
}


class DebugBuildListener : org.gradle.BuildListener {

    override fun settingsEvaluated(settings: Settings) {

    }

    override fun projectsLoaded(gradle: Gradle) {

    }

    override fun buildFinished(result: BuildResult) {
    }

    override fun projectsEvaluated(gradle: Gradle) {
        File(logPath).writeText("")
        gradle.rootProject.allprojects.forEach { project ->
            printJdkForProjectTasks(project)
        }
    }
}


if (ddGradleDebugEnabled) {
    println("datadog.gradle-debug plugin is enabled")
    gradle.addListener(DebugBuildListener())
}
