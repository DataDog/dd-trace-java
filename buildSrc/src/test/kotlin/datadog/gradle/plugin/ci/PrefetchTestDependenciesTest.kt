package datadog.gradle.plugin.ci

import com.sun.net.httpserver.HttpServer
import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections

class PrefetchTestDependenciesTest : GradleFixture() {
  @Test
  fun `prefetch caches runtime artifacts and parent POMs for offline use without running tests`() {
    val repository = createMavenRepoFixture()
    repository.publishVersions("example", "runtime-only", listOf("1.0"))
    repository.publishVersions("example", "transitive", listOf("1.0"))
    writeFile(
      "fake-maven-repo/example/parent/1.0/parent-1.0.pom",
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>example</groupId>
        <artifactId>parent</artifactId>
        <version>1.0</version>
        <packaging>pom</packaging>
      </project>
      """
    )
    writeFile(
      "fake-maven-repo/example/runtime-only/1.0/runtime-only-1.0.pom",
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <parent>
          <groupId>example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0</version>
        </parent>
        <artifactId>runtime-only</artifactId>
        <dependencies>
          <dependency>
            <groupId>example</groupId>
            <artifactId>transitive</artifactId>
            <version>1.0</version>
            <scope>runtime</scope>
          </dependency>
        </dependencies>
      </project>
      """
    )
    val requests = Collections.synchronizedList(mutableListOf<String>())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.use {
        val path = exchange.requestURI.path.removePrefix("/")
        requests.add(path)
        val artifact = File(repository.repoDir, path)
        if (!artifact.isFile) {
          exchange.sendResponseHeaders(404, -1)
        } else if (exchange.requestMethod == "HEAD") {
          exchange.sendResponseHeaders(200, -1)
        } else {
          val bytes = artifact.readBytes()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.write(bytes)
        }
      }
    }
    server.start()
    try {
      writeTestProject("http://127.0.0.1:${server.address.port}")
      val warmed = run("test", "-PskipTests", "-PprefetchTestDependencies", "--no-configuration-cache")

      assertThat(warmed.output).contains("BUILD SUCCESSFUL")
      assertThat(warmed.task(":test")?.outcome).isEqualTo(SKIPPED)
      assertThat(requests).contains(
        "example/parent/1.0/parent-1.0.pom",
        "example/runtime-only/1.0/runtime-only-1.0.jar",
        "example/transitive/1.0/transitive-1.0.jar",
      )
      assertThat(requests).noneMatch { it.contains("unselected") }
    } finally {
      server.stop(0)
    }

    val offline = run("verifyRuntime", "--offline")
    assertThat(offline.output).contains("BUILD SUCCESSFUL")
    assertThat(offline.task(":verifyRuntime")?.outcome).isEqualTo(SUCCESS)
  }

  @Test
  fun `skipped tests do not resolve runtime dependencies unless prefetch is enabled`() {
    writeTestProject(createMavenRepoFixture().repoUrl)

    val result = run("test", "-PskipTests")

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task(":test")?.outcome).isEqualTo(SKIPPED)
  }

  @Test
  fun `prefetch fails when a selected runtime dependency is unavailable`() {
    writeTestProject(createMavenRepoFixture().repoUrl)

    val result = run("test", "-PskipTests", "-PprefetchTestDependencies", expectFailure = true)

    assertThat(result.output).contains("BUILD FAILED", "Could not find example:runtime-only:1.0")
  }

  private fun writeTestProject(repositoryUrl: String) {
    writeSettings("rootProject.name = \"dd-trace-java\"")
    writeRootProject(
      """
      plugins {
        java
        id("dd-trace-java.ci-jobs")
      }
      repositories {
        maven {
          url = uri("$repositoryUrl")
          isAllowInsecureProtocol = true
        }
      }
      dependencies {
        testRuntimeOnly("example:runtime-only:1.0")
      }
      val unselectedRuntime = configurations.create("unselectedRuntime")
      dependencies.add(unselectedRuntime.name, "example:unselected:1.0")
      tasks.register<Test>("unselectedTest") {
        classpath = unselectedRuntime
      }
      tasks.test {
        onlyIf { !providers.gradleProperty("skipTests").isPresent }
        doFirst { error("Tests must not execute during cache warming") }
      }
      tasks.register("verifyRuntime") {
        doLast {
          check(configurations.testRuntimeClasspath.get().files.map { it.name }.toSet() ==
            setOf("runtime-only-1.0.jar", "transitive-1.0.jar"))
        }
      }
      """
    )
  }
}
