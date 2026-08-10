import java.util.regex.Pattern
import java.util.zip.ZipInputStream

plugins {
  java
}

val jarfile = configurations.register("jarfile")

dependencies {
  jarfile("com.datadoghq:dd-trace-ot:$version")
  implementation(gradleApi())
}

abstract class CheckJarContentsTask : DefaultTask() {
  @get:InputFile
  abstract val file: RegularFileProperty

  @get:Input
  abstract val expectedPatterns: ListProperty<String>

  @get:Input
  abstract val excludedPatterns: ListProperty<String>

  @TaskAction
  fun check() {
    val contents = listJarFileContents(file.get().asFile)
    val expected = buildPatterns(expectedPatterns.get())
    val excluded = buildPatterns(excludedPatterns.get())
    checkAllExpectedContentIsPresent(contents, expected)
    checkUnexpectedContent(contents, expected, true)
    checkUnexpectedContent(contents, excluded, false)
  }

  private fun buildPatterns(patterns: List<String>): List<Pattern> = patterns.map { Pattern.compile(it) }

  private fun listJarFileContents(jarFile: java.io.File): List<String> {
    val contents = mutableListOf<String>()
    ZipInputStream(jarFile.inputStream()).use { zipInputStream ->
      var entry = zipInputStream.nextEntry
      while (entry != null) {
        contents.add(entry.name)
        entry = zipInputStream.nextEntry
      }
    }
    return contents
  }

  private fun checkAllExpectedContentIsPresent(contents: Collection<String>, expectedPatterns: Collection<Pattern>) {
    for (expectedPattern in expectedPatterns) {
      val found = contents.any { expectedPattern.matcher(it).matches() }
      if (!found) {
        throw RuntimeException("Unable to find content matching ${expectedPattern.pattern()} in jar file.")
      }
    }
  }

  private fun checkUnexpectedContent(contents: Collection<String>, patterns: Collection<Pattern>, isExpected: Boolean) {
    val unexpectedContent = contents.filter { file ->
      val matches = patterns.any { it.matcher(file).matches() }
      matches != isExpected
    }
    if (unexpectedContent.isNotEmpty()) {
      throw RuntimeException("Found unexpected content in JAR file: ${unexpectedContent.joinToString(", ")}.")
    }
  }
}

val jarFile = layout.file(jarfile.map { it.filter { file -> file.name.startsWith("dd-trace-ot") }.singleFile })

val checkJarContents = tasks.register<CheckJarContentsTask>("checkJarContents") {
  file.set(jarFile)
  expectedPatterns.set(
    listOf(
      "^[^/]*\\.version$",
      "^DDSketch.proto$",
      "^META-INF/.*$",
      "^datadog/.*$",
      "^ddtrot/.*$",
    )
  )
  excludedPatterns.set(
    listOf(
      "^dd-trace-api.version$",
      "^datadog/trace/api/Trace.class$",
      "^datadog/trace/api/Tracer.class$",
      "^datadog/trace/api/config/TracerConfig.class$",
      "^datadog/trace/api/interception/TraceInterceptor.class$",
      "^datadog/trace/api/internal/InternalApi.class$",
      "^datadog/trace/api/sampling/PrioritySampling.class$",
      "^datadog/trace/context/TraceScope.class$",
    )
  )
}

val checkJarSize = tasks.register("checkJarSize") {
  inputs.file(jarFile)
  doLast {
    val jar = jarFile.get().asFile
    // Arbitrary limit to prevent unintentional increases to the dd-trace-ot jar size
    // Raise or lower as required
    if (jar.length() > 8 * 1024 * 1024) {
      throw GradleException("jar size is too big: ${jar.length()} bytes")
    }
  }
}

tasks.check {
  dependsOn(checkJarContents, checkJarSize)
}
