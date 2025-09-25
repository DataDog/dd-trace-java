package datadog.gradle.plugin.dump

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Plugin to collect thread and heap dumps for hanged tests.
 */
class DumpHangedTestPlugin : Plugin<Project> {
  private val logger = Logging.getLogger(DumpHangedTestPlugin::class.java)

  private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
    val thread = Thread(r, "hanged-test-dump")
    thread.isDaemon = true
    thread
  }

  override fun apply(project: Project) {
    fun configure(p: Project) {
      p.tasks.withType<Test>().configureEach {
        val t = this
        t.doFirst { schedule(t) }
        t.doLast { cleanup(t) }
      }
    }

    configure(project)

    if (project == project.rootProject) {
      project.subprojects(::configure)
    }
  }

  private fun schedule(t: Test) {
    val taskName = t.path
    val key = "dumping_future_$taskName"

    if (t.extensions.extraProperties.has(key)) {
      logger.warn("Taking dumps for: $taskName already scheduled.")
      return
    }

    if (!t.timeout.isPresent) {
      logger.warn("No timeout for: $taskName; skipping dumps scheduler")
      return
    }

    logger.warn("Taking dumps for: $taskName scheduled.")

    // Calculate delay for taking dumps as test timeout minus 1 minutes, but no less than 1 minute.
    val delayMinutes = 1L.coerceAtLeast(t.timeout.get().minusMinutes(1).toMinutes())

    val future = scheduler.schedule({
      logger.warn("Taking dumps for: $taskName after $delayMinutes minutes.")

      takeDump(t)
    }, delayMinutes, TimeUnit.MINUTES)

    t.extensions.extraProperties.set(key, future)
  }

  private fun takeDump(t: Test) {
    try {
      // Use Gradle's build dir and adjust for CI artifacts collection if needed.
      val dumpsDir: File = t.project.layout.buildDirectory
        .dir("dumps")
        .map { dir ->
          if (t.project.providers.environmentVariable("CI").isPresent) {
            // Move reports into the folder collected by the collect_reports.sh script.
            File(
              dir.asFile.absolutePath.replace(
                "dd-trace-java/dd-java-agent",
                "dd-trace-java/workspace/dd-java-agent"
              )
            )
          } else {
            dir.asFile
          }
        }
        .get()

      dumpsDir.mkdirs()

      fun file(name: String): File {
        val parts = name.split('.')
        return File(dumpsDir, "${parts.first()}-${System.currentTimeMillis()}.${parts.last()}")
      }

      // For simplicity, use `0` as the PID, which collects all thread dumps across JVMs.
      val allThreadsFile = file("all-thread-dumps.log")
      runCmd(Redirect.to(allThreadsFile), "jcmd", "0", "Thread.print", "-l")

      // Collect all JVMs pids.
      val allJavaProcessesFile = file("all-java-processes.log")
      runCmd(Redirect.to(allJavaProcessesFile), "jcmd", "-l")

      // Collect pids for 'Gradle Test Executor'.
      val pids = allJavaProcessesFile.readLines()
        .filter { it.contains("Gradle Test Executor") }
        .map { it.substringBefore(' ') }

      pids.forEach { pid ->
        // Collect heap dump by pid.
        val heapDumpPath = file("${pid}-heap-dump.hprof").absolutePath
        runCmd(Redirect.INHERIT, "jcmd", pid, "GC.heap_dump", heapDumpPath)

        // Collect thread dump by pid.
        val threadDumpFile = file("${pid}-thread-dump.log")
        runCmd(Redirect.to(threadDumpFile), "jcmd", pid, "Thread.print", "-l")
      }
    } catch (e: Throwable) {
      logger.warn("Taking dumps for: ${t.path} failed with error: ${e.message}.")
    }
  }

  private fun cleanup(t: Test) {
    val future = t.extensions.extraProperties.get("dumping_future_${t.path}") as ScheduledFuture<*>?

    if (future != null && !future.isDone) {
      logger.warn("Taking dump for: ${t.path} canceled with remaining delay of: ${future.getDelay(TimeUnit.SECONDS)} seconds")
      future.cancel(false)
    }
  }

  private fun runCmd(
    redirectTo: Redirect,
    vararg cmd: String
  ): Int =
    ProcessBuilder(*cmd)
      .redirectErrorStream(true)
      .redirectOutput(redirectTo)
      .start()
      .waitFor()
}
