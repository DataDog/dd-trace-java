package datadog.gradle.plugin.csi

import datadog.gradle.plugin.GradleFixture
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class CallSiteInstrumentationPluginTest {
  private val buildGradle = """
    plugins {
      id 'java'
      id 'dd-trace-java.call-site-instrumentation'
    }

    java {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }

    csi {
      suffix = 'CallSite'
      targetFolder = project.layout.buildDirectory.dir('csi')
      rootFolder = file('__ROOT_FOLDER__')
    }

    repositories {
      mavenCentral()
    }

    dependencies {
      implementation group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.8'
      implementation group: 'com.google.auto.service', name: 'auto-service-annotations', version: '1.1.1'
    }
  """.trimIndent()

  @TempDir
  lateinit var buildDir: File

  @Test
  fun `test call site instrumentation plugin`() {
    val fixture = createGradleProject(
      buildGradle,
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

    val result = buildGradleProject(fixture)

    val generated = File(buildDir, "build/csi/BeforeAdviceCallSites.java")
    assertTrue(generated.exists())

    val output = result.output
    assertFalse(output.contains("[⨉]"))
    assertTrue(output.contains("[✓] @CallSite BeforeAdviceCallSite"))
  }

  @Test
  fun `test call site instrumentation plugin with error`() {
    val fixture = createGradleProject(
      buildGradle,
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

    val result = fixture.run("build", "--info", "--stacktrace", forwardOutput = true, expectFailure = true)

    val generated = File(buildDir, "build/csi/BeforeAdviceCallSites.java")
    assertFalse(generated.exists())

    val output = result.output
    assertFalse(output.contains("[✓]"))
    assertTrue(output.contains("ADVICE_METHOD_NOT_STATIC_AND_PUBLIC"))
  }

  private fun createGradleProject(gradleFile: String, advice: String): GradleFixture {
    val fixture = GradleFixture(buildDir)
    val projectFolder = File(System.getProperty("user.dir")).parentFile
    val callSiteJar = File(projectFolder, "buildSrc/call-site-instrumentation-plugin/build/libs/call-site-instrumentation-plugin-all.jar")
    val testCallSiteJarDir = fixture.file("buildSrc/call-site-instrumentation-plugin/build/libs")
    testCallSiteJarDir.mkdirs()

    Files.copy(
      callSiteJar.toPath(),
      testCallSiteJarDir.toPath().resolve(callSiteJar.name)
    )

    val gradleFileContent = gradleFile.replace("__ROOT_FOLDER__", projectFolder.toString().replace("\\", "\\\\"))
    fixture.rootProject(gradleFileContent)

    val advicePackage = parsePackage(advice)
    val adviceClassName = parseClassName(advice)
    val advicePath = if (advicePackage.isEmpty()) {
      "src/main/java/$adviceClassName.java"
    } else {
      "src/main/java/${advicePackage.replace('.', '/')}/$adviceClassName.java"
    }
    fixture.appendTo(advicePath, advice)

    val csiSource = File(projectFolder, "dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/csi")
    csiSource.listFiles()?.forEach { src ->
      fixture.appendTo("src/main/java/datadog/trace/agent/tooling/csi/${src.name}", src.readText())
    }
    return fixture
  }

  private fun buildGradleProject(fixture: GradleFixture): BuildResult =
    fixture.run("build", "--info", "--stacktrace", forwardOutput = true)

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
