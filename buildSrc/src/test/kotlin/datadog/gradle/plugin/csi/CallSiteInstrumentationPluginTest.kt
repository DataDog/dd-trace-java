package datadog.gradle.plugin.csi

import datadog.gradle.plugin.GradleFixture
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CallSiteInstrumentationPluginTest : GradleFixture() {
  private val buildGradle = """
    plugins {
      id("java")
      id("dd-trace-java.call-site-instrumentation")
    }

    java {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }

    csi {
      suffix.set("CallSite")
      targetFolder.set(project.layout.buildDirectory.dir("csi"))
    }

    repositories {
      mavenCentral()
    }

    dependencies {
      implementation("net.bytebuddy:byte-buddy:1.18.10")
      implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    }
  """

  @Test
  fun `test call site instrumentation plugin`() {
    createGradleProject(
      buildGradle,
      """
       import datadog.trace.agent.tooling.csi.*;

       @CallSite(spi = CallSites.class)
       public class BeforeAdviceCallSite {
         @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
         public static void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
         }
       }
      """
    )

    val result = buildGradleProject()

    val generated = buildFile("csi/BeforeAdviceCallSites.java")
    assertTrue(generated.exists())

    val output = result.output
    assertFalse(output.contains("[⨉]"))
    assertTrue(output.contains("[✓] @CallSite BeforeAdviceCallSite"))
  }

  @Test
  fun `test call site instrumentation plugin with error`() {
    createGradleProject(
      buildGradle,
      """
       import datadog.trace.agent.tooling.csi.*;

       @CallSite(spi = CallSites.class)
       public class BeforeAdviceCallSite {
         @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
         private void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
         }
       }
      """
    )

    val result = run("build", "--info", "--stacktrace", forwardOutput = true, expectFailure = true)

    val generated = buildFile("csi/BeforeAdviceCallSites.java")
    assertFalse(generated.exists())

    val output = result.output
    assertFalse(output.contains("[✓]"))
    assertTrue(output.contains("ADVICE_METHOD_NOT_STATIC_AND_PUBLIC"))
  }

  private fun createGradleProject(gradleFile: String, advice: String) {
    val projectFolder = File(System.getProperty("user.dir")).parentFile
    val callSiteJar = File(projectFolder, "buildSrc/call-site-instrumentation-plugin/build/libs/call-site-instrumentation-plugin-all.jar")
    val testCallSiteJarDir = dir("buildSrc/call-site-instrumentation-plugin/build/libs")

    Files.copy(
      callSiteJar.toPath(),
      testCallSiteJarDir.toPath().resolve(callSiteJar.name)
    )

    writeRootProject(gradleFile)

    val advicePackage = parsePackage(advice)
    val adviceClassName = parseClassName(advice)
    val adviceSourceName = if (advicePackage.isEmpty()) {
      adviceClassName
    } else {
      "$advicePackage.$adviceClassName"
    }
    writeJavaSource(adviceSourceName, advice)

    val csiSource = File(projectFolder, "dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/csi")
    csiSource.listFiles()?.forEach { src ->
      writeJavaSource("datadog.trace.agent.tooling.csi.${src.nameWithoutExtension}", src.readText())
    }
  }

  private fun buildGradleProject(): BuildResult =
    run("build", "--info", "--stacktrace", forwardOutput = true)

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
}
