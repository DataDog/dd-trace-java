package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MuzzlePluginPerformanceTest {

  @Test
  fun `task graph does not include muzzle tasks when not requested`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          group = 'com.example.test'
          module = 'some-lib'
          versions = '[1.0.0,2.0.0)'
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:tasks",
      "--all",
      "--info"
    )

    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:tasks")?.outcome)

    assertFalse(
      result.tasks.any() { it.path.contains("muzzle") },
      "Should not create or execute any muzzle tasks when not requested"
    )
    assertTrue(
      result.output.contains("No muzzle tasks invoked for :dd-java-agent:instrumentation:demo, skipping muzzle task planification"),
      "Should log early return when muzzle not requested"
    )
  }

  @Test
  fun `does not configure muzzle when other project muzzle task is requested`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      muzzle {
        pass { coreJdk() }
      }
      """
    )
    fixture.writeNoopScanPlugin()
    fixture.addSubproject("dd-java-agent:instrumentation:other",
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      muzzle {
        pass { coreJdk() }
      }
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      "--info"
    )

    assertTrue(
      result.tasks.any { it.path.contains("demo") && it.path.contains("muzzle") },
      "Should execute muzzle tasks for demo project"
    )
    assertTrue(
      result.tasks.none() { it.path.contains("other") && it.path.contains("muzzle") },
      "Should NOT create or register execute muzzle tasks for other project"
    )
    assertTrue(
      result.output.lines().any { line ->
        line.contains("No muzzle tasks invoked for :dd-java-agent:instrumentation:other, skipping muzzle task planification")
      },
      "Other project should skip muzzle configuration when demo project's muzzle is requested"
    )
  }

  @Test
  fun `muzzle tasks are up-to-date when nothing changes`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    val mavenRepoFixture = MavenRepoFixture(projectDir)

    mavenRepoFixture.publishVersions(
      group = "com.example.test",
      module = "example-lib",
      versions = listOf("1.0.0", "1.1.0")
    )

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      repositories {
        maven {
          url = uri('${mavenRepoFixture.repoUrl}')
          metadataSources {
            mavenPom()
            artifact()
          }
        }
      }

      muzzle {
        pass {
          group = 'com.example.test'
          module = 'example-lib'
          versions = '[1.0.0,2.0.0)'
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = fixture.run(
        ":dd-java-agent:instrumentation:demo:muzzle",
        env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
      )

      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "First run should execute muzzle task")
      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.0.0")?.outcome)
      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.1.0")?.outcome)
      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome,
        "First run should execute muzzle-end task")
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = fixture.run(
        ":dd-java-agent:instrumentation:demo:muzzle",
        env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
      )

      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "First run should execute muzzle task")
      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.0.0")?.outcome,
        "1.0.0 assertion task should be up-to-date")
      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.1.0")?.outcome,
        "1.1.0 assertion task should be up-to-date")
      assertEquals(SUCCESS, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome,
        "First run should execute muzzle-end task")
    }

    // Third run after adding new version - should NOT be up-to-date
    // Add version 1.2.0 to the fake Maven repo
    run {
      mavenRepoFixture.publishVersions(
        group = "com.example.test",
        module = "example-lib",
        versions = listOf("1.2.0")
      )

      val thirdRun = fixture.run(
        ":dd-java-agent:instrumentation:demo:muzzle",
        env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
      )

      assertEquals(UP_TO_DATE, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "First run should execute muzzle task")
      assertEquals(UP_TO_DATE, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.0.0")?.outcome,
        "1.0.0 assertion task should be up-to-date")
      assertEquals(UP_TO_DATE, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.1.0")?.outcome,
        "1.1.0 assertion task should be up-to-date")
      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.2.0")?.outcome,
        "New 1.2.0 assertion task should be created and execute")
      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome,
        "First run should execute muzzle-end task")
    }
  }

  @Test
  fun `muzzle tasks invalidated when instrumentation code changes`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass { coreJdk() }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "First run should execute muzzle task")
      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "First run should execute coreJdk assertion task"
      )
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "Second run should be up-to-date")
      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "coreJdk assertion task should be up-to-date"
      )
    }

    // Third run after changing instrumentation code - should be invalidated
    run {
      val demoSourceDir = File(projectDir, "dd-java-agent/instrumentation/demo/src/main/java/com/example")
      demoSourceDir.mkdirs()
      File(demoSourceDir, "Demo.java").writeText(
        """
        package com.example;

        public class Demo {
          public void doSomething() {}
        }
        """.trimIndent()
      )

      val thirdRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "Third run should execute after instrumentation code change")
      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "coreJdk assertion task should be invalidated and re-execute"
      )
    }
  }

  @Test
  fun `muzzle tasks invalidated when tooling classpath changes`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass { coreJdk() }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "First run should execute muzzle task")
      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "First run should execute coreJdk assertion task"
      )
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "Second run should be up-to-date")
      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "coreJdk assertion task should be up-to-date"
      )
    }

    // Third run after changing agent-tooling code - should be invalidated
    run {
      val toolingSourceDir = File(projectDir, "dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling")
      toolingSourceDir.mkdirs()
      File(toolingSourceDir, "Extra.java").writeText(
        """
        package datadog.trace.agent.tooling;

        public class Extra {
          public void extraMethod() {}
        }
        """.trimIndent()
      )

      val thirdRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "Third run should execute after tooling classpath change")
      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "coreJdk assertion task should be invalidated and re-execute"
      )
    }
  }

  @Test
  fun `muzzle tasks invalidated when bootstrap classpath changes`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass { coreJdk() }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "First run should execute muzzle task")
      assertEquals(SUCCESS, firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "First run should execute coreJdk assertion task"
      )
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "Second run should be up-to-date")
      assertEquals(UP_TO_DATE, secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "coreJdk assertion task should be up-to-date"
      )
    }

    // Third run after changing agent-bootstrap code - should be invalidated
    run {
      val bootstrapSourceDir = File(projectDir, "dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap")
      bootstrapSourceDir.mkdirs()
      File(bootstrapSourceDir, "Helper.java").writeText(
        """
        package datadog.trace.bootstrap;

        public class Helper {
          public void help() {}
        }
        """.trimIndent()
      )

      val thirdRun = fixture.run(":dd-java-agent:instrumentation:demo:muzzle")

      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome,
        "Third run should execute after bootstrap classpath change")
      assertEquals(SUCCESS, thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome,
        "coreJdk assertion task should be invalidated and re-execute"
      )
    }
  }
}
