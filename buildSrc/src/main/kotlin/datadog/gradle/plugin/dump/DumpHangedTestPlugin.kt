package datadog.gradle.plugin.dump

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Plugin to collect thread and heap dumps for hanged tests.
 */
class DumpHangedTestPlugin : Plugin<Project> {
  companion object {
    private const val DUMP_FUTURE_KEY = "dumping_future"
  }

  /** Executor wrapped with proper Gradle lifecycle. */
  abstract class DumpSchedulerService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val executor: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hanged-test-dump").apply { isDaemon = true } }

    fun schedule(task: () -> Unit, delay: Duration): ScheduledFuture<*> =
      executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)

    override fun close() {
      executor.shutdownNow()
    }
  }

  override fun apply(project: Project) {
    val scheduler = project.gradle.sharedServices
      .registerIfAbsent("dumpHangedTestScheduler", DumpSchedulerService::class.java)

    fun configure(p: Project) {
      p.tasks.withType<Test>().configureEach {
        val t = this
        t.doFirst { schedule(t, scheduler) }
        t.doLast { cleanup(t) }
      }
    }

    configure(project)

    if (project == project.rootProject) {
      project.subprojects(::configure)
    }
  }

  private fun schedule(t: Test, scheduler: Provider<DumpSchedulerService>) {
    val taskName = t.path

    if (t.extensions.extraProperties.has(DUMP_FUTURE_KEY)) {
      t.logger.lifecycle("Taking dumps already scheduled for: $taskName")
      return
    }

    if (!t.timeout.isPresent) {
      t.logger.lifecycle("Taking dumps has no timeout configured for: $taskName")
      return
    }

    t.logger.lifecycle("Taking dumps scheduled for: $taskName")

    // Calculate delay for taking dumps as test timeout minus 1 minute, but no less than 1 minute.
    val delay = t.timeout.get().minusMinutes(1).coerceAtLeast(Duration.ofMinutes(1))

    val future = scheduler.get().schedule({
      t.logger.lifecycle("Taking dumps after ${delay.toMinutes()} minutes delay for: $taskName")

      takeDump(t)
    }, delay)

    t.extensions.extraProperties.set(DUMP_FUTURE_KEY, future)
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
      t.logger.warn("Taking dumps failed with error: ${e.message}, for: ${t.path}")
    }
  }

  private fun cleanup(t: Test) {
    val future = t.extensions.extraProperties
      .takeIf { it.has(DUMP_FUTURE_KEY) }
      ?.get(DUMP_FUTURE_KEY) as? ScheduledFuture<*>

    if (future != null && !future.isDone) {
      t.logger.lifecycle("Taking dump canceled with remaining delay of ${future.getDelay(TimeUnit.SECONDS)} seconds for: ${t.path}")
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
