package datadog.gradle.plugin.dump

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JvmVendorSpec
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
import kotlin.jvm.optionals.getOrElse

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
    // Defaults to 180 seconds.
    val dumpOffset: Property<Long> = objects.property(Long::class.java)
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
    if (project.rootProject != project) {
      throw IllegalStateException("Only the root project can apply plugin")
    }

    val scheduler = project.gradle.sharedServices
      .registerIfAbsent("dumpHangedTestScheduler", DumpSchedulerService::class.java)

    val props = project.extensions.create("dumpHangedTest", DumpHangedTestProperties::class.java)

    // Configure test tasks per project from the root plugin instance, only once the `java`
    // plugin (which registers the `Test` tasks) has been applied to a given project.
    project.allprojects {
      pluginManager.withPlugin("java") {
        tasks.withType<Test>().configureEach {
          doFirst {
            // Single source of truth for "is this an IBM JVM", shared with `collectThreadDump`.
            val ibmJvm = isIbmJvmVendor(javaLauncher.get().metadata.vendor)
            if (ibmJvm) {
              // Where IBM/OpenJ9 writes the javacore on SIGQUIT; read at JVM startup, inherited by children.
              environment("IBM_JAVACOREDIR", dumpDirectory(this).absolutePath)
            }
            schedule(this, ibmJvm, scheduler, props)
          }
          doLast { cleanup(this) }
        }
      }
    }
  }

  private fun schedule(
    t: Task,
    ibmJvm: Boolean,
    scheduler: Provider<DumpSchedulerService>,
    props: DumpHangedTestProperties
  ) {
    val taskName = t.path

    if (t.extra.has(DUMP_FUTURE_KEY)) {
      t.logger.info("Taking dumps already scheduled for $taskName")
      return
    }

    val dumpOffset = props.dumpOffset.getOrElse(180)
    val delay = t.timeout.map { it.minusSeconds(dumpOffset) }.orNull

    if (delay == null || delay.seconds < 0) {
      t.logger.info("Taking dumps has invalid timeout configured for $taskName")
      return
    }

    val future = scheduler.get().schedule({
      t.logger.quiet("Taking dumps after ${delay.seconds} seconds delay for $taskName")

      takeDump(t, ibmJvm)
    }, delay)

    t.extra.set(DUMP_FUTURE_KEY, future)
  }

  private fun takeDump(t: Task, ibmJvm: Boolean) {
    try {
      val dumpsDir = dumpDirectory(t)

      if (!dumpsDir.isDirectory && !dumpsDir.mkdirs()) {
        throw IOException("Could not create dump directory $dumpsDir")
      }

      val processes = mutableListOf<ProcessHandle>()
      ProcessHandle.current().children().use { children ->
        children.filter { it.info().commandLine().getOrElse { "" }.contains("Gradle Test Executor") }
          .forEach { process ->
            processes.add(process)

            process.children().use { descendants ->
              descendants.forEach { child -> processes.add(child) }
            }
          }
      }
      if (processes.isEmpty()) {
        t.logger.warn("No Gradle test executors found for ${t.path}; attempting all-JVM thread dumps")
      }

      // Preserve all thread stacks before a slow heap dump can consume the remaining time.
      processes.forEach { process -> collectThreadDump(t, ibmJvm, dumpsDir, process) }

      // Just in case collect all thread dumps by using special PID `0`.
      val allThreadsFile = file(dumpsDir, "all-thread-dumps")
      runCmd(t.logger, t.path, Redirect.to(allThreadsFile), "jcmd", "0", "Thread.print", "-l")
      processes.forEach { process -> collectHeapDump(t, ibmJvm, dumpsDir, process) }
      t.logger.quiet("Finished dump collection for ${t.path}; output directory: $dumpsDir")
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      t.logger.warn("Dump collection interrupted for ${t.path}", e)
    } catch (e: Throwable) {
      t.logger.warn("Taking dumps failed for ${t.path}", e)
    }
  }

  private fun file(baseDir: File, name: String, ext: String = "log") =
    File(baseDir, "$name-${System.currentTimeMillis()}.$ext")

  private fun dumpDirectory(t: Task): File = t.project.layout.buildDirectory
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
    logger: Logger,
    taskPath: String,
    redirectTo: Redirect,
    vararg args: String
  ) {
    val command = args.joinToString(" ")
    val output = redirectTo.file()?.absolutePath ?: "daemon output"
    val start = System.nanoTime()
    logger.quiet("Starting dump command for $taskPath: $command; output: $output")
    var process: Process? = null
    try {
      process = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .redirectOutput(redirectTo)
        .start()
      val exitCode = process.waitFor()

      if (exitCode != 0) {
        throw IOException("Process failed with exit code $exitCode")
      }
      logger.quiet("Completed dump command for $taskPath in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)} ms: $command")
    } catch (e: InterruptedException) {
      process?.destroyForcibly()
      logger.warn("Dump command interrupted for $taskPath: $command; output: $output", e)
      throw e
    } catch (e: Exception) {
      logger.warn("Dump command failed for $taskPath: $command; output: $output", e)
    }
  }

  private fun collectThreadDump(
    t: Task,
    ibmJvm: Boolean,
    baseDir: File,
    process: ProcessHandle
  ) {
    val pid = process.pid().toString()

    if (ibmJvm) {
      // On IBM JDK `kill -3` writes a javacore into the IBM_JAVACOREDIR set when the task started.
      runCmd(t.logger, t.path, Redirect.INHERIT, "kill", "-3", pid)
    } else {
      // Collect thread dump by pid.
      val threadDumpFile = file(baseDir, "$pid-thread-dump", "log")
      runCmd(t.logger, t.path, Redirect.to(threadDumpFile), "jcmd", pid, "Thread.print", "-l")
    }
  }

  private fun collectHeapDump(t: Task, ibmJvm: Boolean, baseDir: File, process: ProcessHandle) {
    if (ibmJvm) {
      // `jcmd GC.heap_dump` is a HotSpot-only diagnostic command.
      return
    }
    val pid = process.pid().toString()
    val heapDumpPath = file(baseDir, "$pid-heap-dump", "hprof").absolutePath
    runCmd(t.logger, t.path, Redirect.INHERIT, "jcmd", pid, "GC.heap_dump", heapDumpPath)
  }
}

/** Whether [vendor] is an IBM JVM (Semeru included), as a toolchain vendor or raw `java.vendor`. */
internal fun isIbmJvmVendor(vendor: String): Boolean = JvmVendorSpec.IBM.matches(vendor)
