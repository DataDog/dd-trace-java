package datadog.gradle.plugin.quality

import datadog.gradle.plugin.muzzle.MuzzlePluginTestFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BuildLogicWorkerTest : MuzzlePluginTestFixture() {
  @ParameterizedTest
  @ValueSource(ints = [8, 11, 17, 25])
  fun `muzzle worker preserves JDK visibility and library isolation`(javaVersion: Int) {
    val javaHome = "JAVA_${javaVersion}_HOME"
    assumeTrue(!System.getenv(javaHome).isNullOrBlank(), "$javaHome is required for this worker test")
    writeGradleProperties("org.gradle.java.installations.fromEnv=$javaHome")
    writeProject(
      """
      plugins {
        java
        id("dd-trace-java.muzzle")
      }
      muzzle { pass { coreJdk("$javaVersion") } }
      """
    )
    writeFile(
      "dd-java-agent/agent-tooling/build.gradle.kts",
      """
      tasks.withType<JavaCompile>().configureEach { options.release = 8 }
      """,
      append = true,
    )
    writeScanPlugin(
      """
      if (!"${if (javaVersion == 8) "1.8" else javaVersion.toString()}".equals(System.getProperty("java.specification.version"))) {
        throw new AssertionError("Expected the scan to execute in a Java $javaVersion worker");
      }
      for (ClassLoader loader : new ClassLoader[] {instrumentationClassLoader, testApplicationClassLoader}) {
        try {
          if (loader.loadClass("java.sql.Driver") != java.sql.Driver.class) {
            throw new AssertionError("JDK classes must be visible");
          }
          Class<?> cryptoProvider = javax.crypto.Cipher.getInstance("AES").getProvider().getClass();
          if (loader.loadClass(cryptoProvider.getName()) != cryptoProvider) {
            throw new AssertionError("Java 8 extension classes must be visible");
          }
        } catch (ClassNotFoundException | java.security.GeneralSecurityException e) {
          throw new AssertionError(e);
        }
        for (String name : new String[] {"org.gradle.api.Project", "com.google.common.collect.ImmutableList", "kotlin.Unit"}) {
          try {
            loader.loadClass(name);
            throw new AssertionError("Gradle dependency leaked: " + name);
          } catch (ClassNotFoundException expected) {
          }
        }
      }
      """
    )

    val result = run(":dd-java-agent:instrumentation:demo:muzzle", "--stacktrace")

    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
      .withFailMessage("%s", result.output)
      .isEqualTo(SUCCESS)
  }
}
