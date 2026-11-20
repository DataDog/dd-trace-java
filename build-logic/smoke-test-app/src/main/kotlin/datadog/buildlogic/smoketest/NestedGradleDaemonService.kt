package datadog.buildlogic.smoketest

import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Stops nested daemons after their consuming tasks finish, retaining their user homes and caches. */
abstract class NestedGradleDaemonService :
  BuildService<BuildServiceParameters.None>,
  AutoCloseable {
  private val environments = ConcurrentHashMap<Pair<File, String>, Map<String, String>>()

  fun register(userHome: File, version: String, javaHome: File, environment: Map<String, String>) {
    environments.putIfAbsent(userHome to version, environment + ("JAVA_HOME" to javaHome.absolutePath))
  }

  override fun close() {
    for ((homeAndVersion, environment) in environments) {
      val (userHome, version) = homeAndVersion
      try {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        stopDaemons(userHome, version, environment)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.warn("Interrupted while stopping nested Gradle daemons in {}", userHome, e)
        return
      } catch (e: Exception) {
        logger.warn("Could not stop nested Gradle {} daemons in {}", version, userHome, e)
      }
    }
  }

  private fun stopDaemons(userHome: File, version: String, environment: Map<String, String>) {
    val daemons = userHome.resolve("daemon/$version").walkTopDown()
      .filter { it.isFile && it.name.startsWith("daemon-") && it.name.endsWith(".out.log") }
      .mapNotNull { log ->
        val pid = log.name.removePrefix("daemon-").removeSuffix(".out.log").toLongOrNull()?.takeIf { it > 0 }
          ?: return@mapNotNull null
        val process = ProcessHandle.of(pid).orElse(null) ?: return@mapNotNull null
        val info = process.info()
        val commandLine = info.commandLine().orElse(null)
        // Windows may omit command lines; the start time also guards against reused PIDs.
        if ((commandLine == null || commandLine.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon")) &&
          info.startInstant().map { it.toEpochMilli() <= log.lastModified() }.orElse(true)
        ) {
          process
        } else {
          null
        }
      }.toList()
    val executableName = if (isWindows()) "gradle.bat" else "gradle"
    val executable = userHome.resolve("wrapper/dists/gradle-$version-bin").walkTopDown()
      .firstOrNull { it.isFile && it.name == executableName && it.parentFile?.name == "bin" }
    if (executable == null) {
      if (daemons.isNotEmpty()) logger.warn("Could not find Gradle {} to stop nested daemons in {}", version, userHome)
      return
    }

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_TIMEOUT_SECONDS)
    val process = ProcessBuilder(executable.absolutePath, "--stop", "--gradle-user-home", userHome.absolutePath)
      .directory(userHome)
      .apply {
        environment().apply {
          clear()
          putAll(environment)
        }
      }
      .inheritIO()
      .start()
    try {
      if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn("Timed out while stopping nested Gradle {} daemons in {}", version, userHome)
        return
      }
      if (process.exitValue() != 0) {
        logger.warn("Nested Gradle {} daemon stop exited with code {}", version, process.exitValue())
      }
      // --stop acknowledges shutdown before shutdown hooks and file writes finish.
      for (daemon in daemons) {
        daemon.onExit().get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
      }
    } finally {
      if (process.isAlive) process.destroyForcibly()
    }
  }

  companion object {
    private val logger = Logging.getLogger(NestedGradleDaemonService::class.java)
    private const val STOP_TIMEOUT_SECONDS = 30L
  }
}
