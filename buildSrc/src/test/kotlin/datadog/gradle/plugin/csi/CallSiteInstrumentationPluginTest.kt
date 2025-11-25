package datadog.gradle.plugin.csi

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class CallSiteInstrumentationPluginTest {
  private val buildGradle = """
    plugins {
      id 'java'
      id 'call-site-instrumentation'
    }

    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    csi {
      suffix = 'CallSite'
      targetFolder = project.layout.buildDirectory.dir('csi')
      rootFolder = file('__ROOT_FOLDER__')
    }
  
    repositories {
      mavenCentral()
    }
  
    dependencies {
      implementation group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.1'
      implementation group: 'com.google.auto.service', name: 'auto-service-annotations', version: '1.1.1'
    }
  """.trimIndent()

  @TempDir
  lateinit var buildDir: File

  @Test
  fun `test call site instrumentation plugin`() {
    createGradleProject(
      buildDir, buildGradle,
      """
       import datadog.trace.agent.tooling.csi.*;
  
       @CallSite(spi = CallSites.class)
       public class BeforeAdviceCallSite {
         @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
         public static void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
         }
       }
      """.trimIndent()
    )

    val result = buildGradleProject(buildDir)

    val generated = resolve(buildDir, "build", "csi", "BeforeAdviceCallSites.java")
    assertTrue(generated.exists())

    val output = result.output
    assertFalse(output.contains("[⨉]"))
    assertTrue(output.contains("[✓] @CallSite BeforeAdviceCallSite"))
  }

  @Test
  fun `test call site instrumentation plugin with error`() {
    createGradleProject(
      buildDir, buildGradle,
      """
       import datadog.trace.agent.tooling.csi.*;
  
       @CallSite(spi = CallSites.class)
       public class BeforeAdviceCallSite {
         @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
         private void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
         }
       }
      """.trimIndent()
    )

    val error = assertThrows(UnexpectedBuildFailure::class.java) {
      buildGradleProject(buildDir)
    }

    val generated = resolve(buildDir, "build", "csi", "BeforeAdviceCallSites.java")
    assertFalse(generated.exists())

    val output = error.message ?: ""
    assertFalse(output.contains("[✓]"))
    assertTrue(output.contains("ADVICE_METHOD_NOT_STATIC_AND_PUBLIC"))
  }

  private fun createGradleProject(buildDir: File, gradleFile: String, advice: String) {
    val projectFolder = File(System.getProperty("user.dir")).parentFile
    val callSiteJar = resolve(projectFolder, "buildSrc", "call-site-instrumentation-plugin", "build", "libs", "call-site-instrumentation-plugin-all.jar")
    val testCallSiteJarDir = resolve(buildDir, "buildSrc", "call-site-instrumentation-plugin", "build", "libs")
    testCallSiteJarDir.mkdirs()

    Files.copy(
      callSiteJar.toPath(),
      testCallSiteJarDir.toPath().resolve(callSiteJar.name)
    )

    val gradleFileContent = gradleFile.replace("__ROOT_FOLDER__", projectFolder.toString().replace("\\", "\\\\"))
    val buildGradle = resolve(buildDir, "build.gradle")
    buildGradle.writeText(gradleFileContent)

    val javaFolder = resolve(buildDir, "src", "main", "java")
    javaFolder.mkdirs()

    val advicePackage = parsePackage(advice)
    val adviceClassName = parseClassName(advice)
    val adviceFolder = resolve(javaFolder, *advicePackage.split("\\.").toTypedArray())
    adviceFolder.mkdirs()

    val adviceFile = resolve(adviceFolder, "$adviceClassName.java")
    adviceFile.writeText(advice)

    val csiSource = resolve(projectFolder, "dd-java-agent", "agent-tooling", "src", "main", "java", "datadog", "trace", "agent", "tooling", "csi")
    val csiTarget = resolve(javaFolder, "datadog", "trace", "agent", "tooling", "csi")
    csiTarget.mkdirs()
    csiSource.listFiles()?.forEach {
      val targetFile = File(csiTarget, it.name)
      targetFile.writeText(it.readText())
    }
  }

  private fun buildGradleProject(buildDir: File): BuildResult {
    return GradleRunner.create()
      .withTestKitDir(File(buildDir, ".gradle-test-kit")) // workaround in case the global test-kit cache becomes corrupted
      .withDebug(true) // avoids starting daemon which can leave undeleted files post-cleanup
      .withProjectDir(buildDir)
      .withArguments("build", "--info", "--stacktrace")
      .withPluginClasspath()
      .forwardOutput()
      .build()
  }

  private fun parsePackage(advice: String): String {
    val regex = Regex("package\\s+([\\w.]+)\\s*;", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(advice)
    return match?.groupValues?.getOrNull(1) ?: ""
  }

  private fun parseClassName(advice: String): String {
    val regex = Regex("class\\s+(\\w+)\\s+\\{", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(advice)
    return match?.groupValues?.getOrNull(1) ?: ""
  }

  private fun resolve(parent: File, vararg path: String): File {
    return path.fold(parent) { acc, next -> File(acc, next) }
  }
}
