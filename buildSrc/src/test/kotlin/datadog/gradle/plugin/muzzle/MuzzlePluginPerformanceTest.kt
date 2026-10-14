package datadog.gradle.plugin.muzzle

import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.assertj.core.api.Assertions.assertThat
import kotlin.math.abs

class MuzzlePluginPerformanceTest : MuzzlePluginTestFixture() {
  private fun writeCoreProject() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle { pass { coreJdk() } }
      """
    )
    writeNoopScanPlugin()
  }

  @Test
  fun `compileMuzzle does not resolve version directives or schedule checks`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle {
        pass {
          group = "does.not.exist"
          module = "must-not-resolve"
          versions = "[1.0,2.0)"
        }
      }
      """
    )
    val result = run(":dd-java-agent:instrumentation:demo:compileMuzzle", "--offline", "--info")
    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.tasks).noneMatch { it.path.contains("muzzle-Assert") || it.path.endsWith("muzzle-end") }
    assertThat(result.task(":dd-java-agent:instrumentation:demo:compileMuzzle")?.outcome).isEqualTo(UP_TO_DATE)
  }

  @Test
  fun `aggregate does not plan an excluded module`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle {
        pass {
          group = "does.not.exist"
          module = "must-not-resolve"
          versions = "[1.0,2.0)"
        }
      }
      """
    )
    writeRootProject(
      """
      tasks.register("runMuzzle") { dependsOn(":dd-java-agent:instrumentation:demo:muzzle") }
      """
    )

    val result = run(":runMuzzle", "-x", ":dd-java-agent:instrumentation:demo:muzzle", "--offline")

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.tasks).noneMatch { it.path.startsWith(":dd-java-agent:instrumentation:demo:") }
    assertThat(result.output).doesNotContain("muzzle-Assert", "muzzle-end")
  }

  @Test
  fun `aggregate skips excluded plans but explicit muzzle still checks them`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle { pass { coreJdk() } }
      """
    )
    writeNoopScanPlugin()
    addSubproject("dd-java-agent:instrumentation:other",
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle { pass { coreJdk() } }
      """
    )
    writeRootProject(
      """
      tasks.register("runMuzzle") { dependsOn(":dd-java-agent:instrumentation:demo:muzzle") }
      """
    )
    val aggregate = run("runMuzzle", "--info")
    assertThat(aggregate.output).contains("BUILD SUCCESSFUL")
    assertThat(aggregate.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome).isEqualTo(SUCCESS)
    assertThat(aggregate.tasks).noneMatch { it.path.contains(":other:") }
    val explicit = run(":dd-java-agent:instrumentation:other:muzzle")
    assertThat(explicit.output).contains("BUILD SUCCESSFUL")
    assertThat(explicit.task(":dd-java-agent:instrumentation:other:muzzle-AssertPass-core-jdk")?.outcome).isEqualTo(SUCCESS)
  }


  @Test
  fun `task graph does not include muzzle tasks when not requested`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle {
        pass {
          group = "does.not.exist"
          module = "must-not-resolve"
          versions = "[1.0.0,2.0.0)"
        }
      }
      """
    )
    writeNoopScanPlugin()

    val result = run(
      ":dd-java-agent:instrumentation:demo:tasks",
      "--all",
      "--offline",
      "--info"
    )

    assertThat(result.task(":dd-java-agent:instrumentation:demo:tasks")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.output).contains("BUILD SUCCESSFUL")

    assertThat(result.tasks)
      .withFailMessage("Should not create or execute any muzzle tasks when not requested")
      .noneMatch { it.path.contains("muzzle") }
    assertThat(result.output).doesNotContain("muzzle-Assert", "muzzle-end")
  }

  @Test
  fun `does not plan checks when another project's muzzle task is requested`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle {
        pass { coreJdk() }
      }
      """
    )
    writeNoopScanPlugin()
    addSubproject("dd-java-agent:instrumentation:other",
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle {
        pass {
          group = "does.not.exist"
          module = "must-not-resolve"
          versions = "[1.0,2.0)"
        }
      }
      """
    )

    val result = run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--offline",
      "--stacktrace",
      "--info"
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.tasks)
      .withFailMessage("Should execute muzzle tasks for demo project")
      .anyMatch { it.path.contains("demo") && it.path.contains("muzzle") }
    assertThat(result.tasks)
      .withFailMessage("Should NOT create or register execute muzzle tasks for other project")
      .noneMatch { it.path.contains("other") && it.path.contains("muzzle") }
  }

  @ParameterizedTest
  @ValueSource(ints = [1, 2])
  fun `CI aggregate runs only the module assigned to its slot`(slot: Int) {
    writeCoreProject()
    addSubproject("dd-java-agent:instrumentation:other",
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle { pass { coreJdk() } }
      """
    )
    writeRootProject("""plugins { id("dd-trace-java.ci-jobs") }""")
    val demoSlot = abs(":dd-java-agent:instrumentation:demo".hashCode() % 2) + 1
    val otherSlot = abs(":dd-java-agent:instrumentation:other".hashCode() % 2) + 1
    assertThat(demoSlot).isNotEqualTo(otherSlot)

    val result = run(":runMuzzle", "-Pslot=$slot/2")

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    val selectedModule = if (slot == demoSlot) "demo" else "other"
    val unselectedModule = if (slot == demoSlot) "other" else "demo"
    assertThat(result.task(":dd-java-agent:instrumentation:$selectedModule:muzzle-AssertPass-core-jdk")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.tasks).noneMatch { it.path.contains(":$unselectedModule:") }
  }

  @Test
  fun `CI aggregate does not resolve off-slot directives`() {
    writeCoreProject()
    addSubproject("dd-java-agent:instrumentation:other",
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      muzzle {
        pass {
          group = "does.not.exist"
          module = "must-not-resolve"
          versions = "[1.0,2.0)"
        }
      }
      """
    )
    writeRootProject("""plugins { id("dd-trace-java.ci-jobs") }""")
    val demoSlot = abs(":dd-java-agent:instrumentation:demo".hashCode() % 2) + 1

    val result = run(":runMuzzle", "-Pslot=$demoSlot/2", "--offline")

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.tasks).noneMatch { it.path.contains(":other:") }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `explicit module check bypasses CI slot selection`(includeAggregate: Boolean) {
    writeCoreProject()
    writeRootProject("""plugins { id("dd-trace-java.ci-jobs") }""")
    val demoSlot = abs(":dd-java-agent:instrumentation:demo".hashCode() % 2) + 1
    val taskNames = if (includeAggregate) {
      arrayOf(":runMuzzle", ":dd-java-agent:instrumentation:demo:muzzle")
    } else {
      arrayOf(":dd-java-agent:instrumentation:demo:muzzle")
    }

    val result = run(*taskNames, "-Pslot=${3 - demoSlot}/2")

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
      .isEqualTo(SUCCESS)
  }

  @Test
  fun `muzzle tasks are up-to-date when nothing changes`() {
    val mavenRepoFixture = createMavenRepoFixture()

    mavenRepoFixture.publishVersions(
      group = "com.example.test",
      module = "example-lib",
      versions = listOf("1.0.0", "1.1.0")
    )

    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      repositories {
        maven {
          url = uri("${mavenRepoFixture.repoUrl}")
          metadataSources {
            mavenPom()
            artifact()
          }
        }
      }

      muzzle {
        pass {
          group = "com.example.test"
          module = "example-lib"
          versions = "[1.0.0,2.0.0)"
        }
      }
      """
    )
    writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = run(
        ":dd-java-agent:instrumentation:demo:muzzle",
        env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
      )

      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("First run should execute muzzle task")
        .isEqualTo(SUCCESS)
      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.0.0")?.outcome)
        .isEqualTo(SUCCESS)
      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.1.0")?.outcome)
        .isEqualTo(SUCCESS)
      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)
        .withFailMessage("First run should execute muzzle-end task")
        .isEqualTo(SUCCESS)
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = run(
        ":dd-java-agent:instrumentation:demo:muzzle",
        env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
      )

      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("First run should execute muzzle task")
        .isEqualTo(UP_TO_DATE)
      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.0.0")?.outcome)
        .withFailMessage("1.0.0 assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.1.0")?.outcome)
        .withFailMessage("1.1.0 assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)
        .withFailMessage("First run should execute muzzle-end task")
        .isEqualTo(SUCCESS)
    }

    // Third run after adding new version - should NOT be up-to-date
    // Add version 1.2.0 to the fake Maven repo
    run {
      mavenRepoFixture.publishVersions(
        group = "com.example.test",
        module = "example-lib",
        versions = listOf("1.2.0")
      )

      val thirdRun = run(
        ":dd-java-agent:instrumentation:demo:muzzle",
        env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
      )

      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("First run should execute muzzle task")
        .isEqualTo(UP_TO_DATE)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.0.0")?.outcome)
        .withFailMessage("1.0.0 assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.1.0")?.outcome)
        .withFailMessage("1.1.0 assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-example-lib-1.2.0")?.outcome)
        .withFailMessage("New 1.2.0 assertion task should be created and execute")
        .isEqualTo(SUCCESS)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)
        .withFailMessage("First run should execute muzzle-end task")
        .isEqualTo(SUCCESS)
    }
  }

  @Test
  fun `muzzle tasks invalidated when instrumentation code changes`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle {
        pass { coreJdk() }
      }
      """
    )
    writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("First run should execute muzzle task")
        .isEqualTo(SUCCESS)
      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("First run should execute coreJdk assertion task")
        .isEqualTo(SUCCESS)
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("Second run should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("coreJdk assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
    }

    // Third run after changing instrumentation code - should be invalidated
    run {
      writeFile(
        "dd-java-agent/instrumentation/demo/src/main/java/com/example/Demo.java",
        """
        package com.example;

        public class Demo {
          public void doSomething() {}
        }
        """
      )

      val thirdRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("Third run should execute after instrumentation code change")
        .isEqualTo(SUCCESS)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("coreJdk assertion task should be invalidated and re-execute")
        .isEqualTo(SUCCESS)
    }
  }

  @Test
  fun `muzzle tasks invalidated when tooling classpath changes`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle {
        pass { coreJdk() }
      }
      """
    )
    writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("First run should execute muzzle task")
        .isEqualTo(SUCCESS)
      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("First run should execute coreJdk assertion task")
        .isEqualTo(SUCCESS)
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("Second run should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("coreJdk assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
    }

    // Third run after changing agent-tooling code - should be invalidated
    run {
      writeFile(
        "dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Extra.java",
        """
        package datadog.trace.agent.tooling;

        public class Extra {
          public void extraMethod() {}
        }
        """
      )

      val thirdRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("Third run should execute after tooling classpath change")
        .isEqualTo(SUCCESS)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("coreJdk assertion task should be invalidated and re-execute")
        .isEqualTo(SUCCESS)
    }
  }

  @Test
  fun `muzzle tasks invalidated when bootstrap classpath changes`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle {
        pass { coreJdk() }
      }
      """
    )
    writeNoopScanPlugin()

    // First run - should execute the tasks
    run {
      val firstRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("First run should execute muzzle task")
        .isEqualTo(SUCCESS)
      assertThat(firstRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("First run should execute coreJdk assertion task")
        .isEqualTo(SUCCESS)
    }

    // Second run without changes - assertion tasks should be up-to-date
    run {
      val secondRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("Second run should be up-to-date")
        .isEqualTo(UP_TO_DATE)
      assertThat(secondRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("coreJdk assertion task should be up-to-date")
        .isEqualTo(UP_TO_DATE)
    }

    // Third run after changing agent-bootstrap code - should be invalidated
    run {
      writeFile(
        "dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Helper.java",
        """
        package datadog.trace.bootstrap;

        public class Helper {
          public void help() {}
        }
        """
      )

      val thirdRun = run(":dd-java-agent:instrumentation:demo:muzzle")

      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
        .withFailMessage("Third run should execute after bootstrap classpath change")
        .isEqualTo(SUCCESS)
      assertThat(thirdRun.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .withFailMessage("coreJdk assertion task should be invalidated and re-execute")
        .isEqualTo(SUCCESS)
    }
  }
}
