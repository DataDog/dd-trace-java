package datadog.gradle.plugin.dump

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Plugin to collect thread and heap dumps for hanged tests.
 */
@Suppress("unused")
class DumpHangedTestPlugin : Plugin<Project> {
  companion object {
    private const val DUMP_FUTURE_KEY = "dumping_future"
  }

  /** Plugin properties */
  abstract class DumpHangedTestProperties @Inject constructor(objects: ObjectFactory) {
    // Time offset (in seconds) before a test reaches its timeout at which dumps should be started.
    // Defaults to 60 seconds.
    val dumpOffset: Property<Long> = objects.property(Long::class.java)
  }

  /** Executor wrapped with proper Gradle lifecycle. */
  abstract class DumpSchedulerService :
    BuildService<BuildServiceParameters.None>,
    AutoCloseable {
    private val executor: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hanged-test-dump").apply { isDaemon = true } }

    fun schedule(task: () -> Unit, delay: Duration): ScheduledFuture<*> = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)

    override fun close() {
      executor.shutdownNow()
    }
  }

  override fun apply(project: Project) {
    if (project.rootProject != project) {
      return
    }

    val scheduler = project.gradle.sharedServices
      .registerIfAbsent("dumpHangedTestScheduler", DumpSchedulerService::class.java)

    // Create plugin properties.
    val props = project.extensions.create("dumpHangedTest", DumpHangedTestProperties::class.java)

    fun configure(p: Project) {
      p.tasks.withType<Test>().configureEach {
        doFirst { schedule(this, scheduler, props) }
        doLast { cleanup(this) }
      }
    }

    configure(project)

    project.subprojects(::configure)
  }

  private fun schedule(t: Task, scheduler: Provider<DumpSchedulerService>, props: DumpHangedTestProperties) {
    val taskName = t.path

    if (t.extra.has(DUMP_FUTURE_KEY)) {
      t.logger.info("Taking dumps already scheduled for $taskName")
      return
    }

    val dumpOffset = props.dumpOffset.getOrElse(60)
    val delay = t.timeout.map { it.minusSeconds(dumpOffset) }.orNull

    if (delay == null || delay.seconds < 0) {
      t.logger.info("Taking dumps has invalid timeout configured for $taskName")
      return
    }

    val future = scheduler.get().schedule({
      t.logger.quiet("Taking dumps after ${delay.seconds} seconds delay for $taskName")

      takeDump(t)
    }, delay)

    t.extra.set(DUMP_FUTURE_KEY, future)
  }

  private fun takeDump(t: Task) {
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

      fun file(name: String, ext: String = "log") = File(dumpsDir, "$name-${System.currentTimeMillis()}.$ext")

      // For simplicity, use `0` as the PID, which collects all thread dumps across JVMs.
      val allThreadsFile = file("all-thread-dumps")
      runCmd(Redirect.to(allThreadsFile), "jcmd", "0", "Thread.print", "-l")

      // Collect all JVMs pids.
      val allJavaProcessesFile = file("all-java-processes")
      runCmd(Redirect.to(allJavaProcessesFile), "jcmd", "-l")

      // Collect pids for 'Gradle Test Executor'.
      val pids = allJavaProcessesFile.readLines()
        .filter { it.contains("Gradle Test Executor") }
        .map { it.substringBefore(' ') }

      pids.forEach { pid ->
        // Collect heap dump by pid.
        val heapDumpPath = file("$pid-heap-dump", "hprof").absolutePath
        runCmd(Redirect.INHERIT, "jcmd", pid, "GC.heap_dump", heapDumpPath)

        // Collect thread dump by pid.
        val threadDumpFile = file("$pid-thread-dump")
        runCmd(Redirect.to(threadDumpFile), "jcmd", pid, "Thread.print", "-l")
      }
    } catch (e: Throwable) {
      t.logger.warn("Taking dumps failed with error: ${e.message}, for ${t.path}")
    }
  }

  private fun cleanup(t: Task) {
    val future = t.extra
      .takeIf { it.has(DUMP_FUTURE_KEY) }
      ?.get(DUMP_FUTURE_KEY) as? ScheduledFuture<*>

    if (future != null && !future.isDone) {
      t.logger.info("Taking dump canceled with remaining delay of ${future.getDelay(TimeUnit.SECONDS)} seconds for ${t.path}")
      future.cancel(false)
    }
  }

  private fun runCmd(
    redirectTo: Redirect,
    vararg args: String
  ) {
    val exitCode = ProcessBuilder(*args)
      .redirectErrorStream(true)
      .redirectOutput(redirectTo)
      .start()
      .waitFor()

    if (exitCode != 0) {
      throw IOException("Process failed: ${args.joinToString(" ")}, exit code: $exitCode")
    }
  }
}
