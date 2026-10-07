package datadog.gradle.plugin.observer

import java.io.File
import java.net.JarURLConnection
import java.util.jar.JarFile

/** Finds the observer agent attached to this Gradle JVM, without caller artifact metadata. */
object ObserverAgentSelection {
  private const val RUNTIME = "datadog.trace.observer.bootstrap.ObserverRuntime"

  private val attachedJar: File? by lazy {
    val runtime =
      try {
        Class.forName(RUNTIME, false, null)
      } catch (absent: ClassNotFoundException) {
        return@lazy null
      }
    val resource = runtime.getResource("ObserverRuntime.class")
    check(resource != null && resource.protocol == "jar") { "Attached observer has no jar resource" }
    val jar = File((resource.openConnection() as JarURLConnection).jarFileURL.toURI()).canonicalFile
    val attribute = runtime.getField("MANIFEST_ATTRIBUTE").get(null) as String
    val version = runtime.getField("MANIFEST_VERSION").get(null) as String
    JarFile(jar).use {
      require(it.manifest.mainAttributes.getValue(attribute) == version) { "Not an observer artifact: $jar" }
    }
    jar
  }

  @JvmStatic
  fun attached(): File? = attachedJar

  @JvmStatic
  fun validate(): File =
    requireNotNull(attachedJar) {
      "traceTracer requires an observer attached to the Gradle daemon with -javaagent"
    }

  @JvmStatic
  fun configurationFingerprint(): String =
    Class.forName(RUNTIME, false, null).getMethod("configurationFingerprint").invoke(null) as String

  /** Whether a `-javaagent:` argument points at the attached observer, whatever path spelling it uses. */
  @JvmStatic
  fun isAttachedAgentArgument(argument: String): Boolean = attachedJar?.let { pointsAt(it, argument) } ?: false

  internal fun pointsAt(jar: File, argument: String): Boolean =
    argument.startsWith("-javaagent:") &&
      File(argument.removePrefix("-javaagent:").substringBefore('=')).canonicalFile == jar.canonicalFile
}
