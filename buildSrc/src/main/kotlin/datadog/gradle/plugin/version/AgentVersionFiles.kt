package datadog.gradle.plugin.version

import org.gradle.api.file.FileTree
import java.io.File
import java.util.jar.JarFile

/** Checks the generated resources excluded from smoke-test classpath fingerprints. */
object AgentVersionFiles {
  @JvmStatic
  fun expectedPaths(resources: FileTree, dependencies: Iterable<File>): Set<String> {
    val paths = mutableSetOf<String>()
    resources.visit {
      if (!isDirectory && name.endsWith(".version")) {
        paths.add(relativePath.pathString)
      }
    }
    dependencies.forEach { dependency ->
      JarFile(dependency).use { jar -> paths.addAll(versionPaths(jar)) }
    }
    return paths
  }

  @JvmStatic
  fun validate(jar: JarFile, expectedPaths: Set<String>, expectedVersion: String): List<String> {
    val actualPaths = versionPaths(jar)
    val failures = mutableListOf<String>()

    (expectedPaths - actualPaths).sorted().forEach {
      failures.add("Missing version resource: $it")
    }
    (actualPaths - expectedPaths).sorted().forEach {
      failures.add("Unexpected version resource: $it")
    }
    (expectedPaths intersect actualPaths).sorted().forEach { path ->
      val actualVersion = jar.getInputStream(jar.getJarEntry(path)).bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
      if (actualVersion != expectedVersion) {
        failures.add("Incorrect version resource: $path (expected $expectedVersion)")
      }
    }

    return failures
  }

  private fun versionPaths(jar: JarFile): Set<String> = jar.entries().asSequence()
    .filter { !it.isDirectory && it.name.endsWith(".version") }
    .map { it.name }
    .toSet()
}
