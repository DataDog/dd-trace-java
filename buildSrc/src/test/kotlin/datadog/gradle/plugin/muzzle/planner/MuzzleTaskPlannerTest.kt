package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.junit.jupiter.api.Test

class MuzzleTaskPlannerTest {

  @Test
  fun `empty directives list returns empty plans`() {
    val fakeService = FakeResolutionService()

    val plans = MuzzleTaskPlanner(fakeService).plan(emptyList())

    assertThat(plans).isEmpty()
    assertThat(fakeService.resolveCalls).isEqualTo(0)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `directive with no resolved artifacts returns empty plans`() {
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "nonexistent"
      versions = "[99.0,100.0)"
      assertPass = true
    }
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(directive to emptySet())
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertThat(plans).isEmpty()
    assertThat(fakeService.resolveCalls).isEqualTo(1)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `coreJdk directive does not call resolution service`() {
    val directive = MuzzleDirective().apply {
      assertPass = true
      coreJdk()
    }
    val fakeService = FakeResolutionService()

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertThat(plans).containsExactly(MuzzleTaskPlan(directive, null))
    assertThat(fakeService.resolveCalls).isEqualTo(0)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `artifact directive creates one plan per resolved artifact version`() {
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "demo"
      versions = "[1.0,2.0)"
      assertPass = true
    }
    val artifacts = linkedSetOf(
      artifact(version = "1.0.0"),
      artifact(version = "1.1.0"),
      artifact(version = "1.2.0"),
      artifact(version = "1.3.0")
    )
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(directive to artifacts)
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertThat(plans).containsExactly(
      MuzzleTaskPlan(directive, artifact(version = "1.0.0")),
      MuzzleTaskPlan(directive, artifact(version = "1.1.0")),
      MuzzleTaskPlan(directive, artifact(version = "1.2.0")),
      MuzzleTaskPlan(directive, artifact(version = "1.3.0")),
    )
    assertThat(fakeService.resolveCalls).isEqualTo(1)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `multiple directives processed together preserves order`() {
    val directive1 = MuzzleDirective().apply {
      group = "com.example"
      module = "first"
      versions = "[1.0,2.0)"
      assertPass = true
    }
    val directive2 = MuzzleDirective().apply {
      group = "com.example"
      module = "second"
      versions = "[2.0,3.0)"
      assertPass = true
    }
    val directive3 = MuzzleDirective().apply {
      group = "com.example"
      module = "third"
      versions = "[3.0,4.0)"
      assertPass = false
    }
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        directive1 to linkedSetOf(artifact("first", "1.5.0")),
        directive2 to linkedSetOf(artifact("second", "2.5.0")),
        directive3 to linkedSetOf(artifact("third", "3.5.0"))
      )
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive1, directive2, directive3))

    assertThat(plans).containsExactly(
      MuzzleTaskPlan(directive1, artifact("first", "1.5.0")),
      MuzzleTaskPlan(directive2, artifact("second", "2.5.0")),
      MuzzleTaskPlan(directive3, artifact("third", "3.5.0")),
    )
    assertThat(fakeService.resolveCalls).isEqualTo(3)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `assertInverse adds inverse plans on top of declared range plans`() {
    val directive = MuzzleDirective().apply {
      group = "com.example"
      module = "demo"
      versions = "[3.0,)"
      assertPass = true
      assertInverse = true
    }
    val inversedDirective = MuzzleDirective().apply {
      group = "com.example"
      module = "demo"
      versions = "[2.7,3.0)"
      assertPass = false
    }
    val directArtifactV1 = artifact(version = "3.12.13")
    val directArtifactV2 = artifact(version = "4.4.1")
    val directArtifactV3 = artifact(version = "5.3.2")
    val inverseArtifactV1 = artifact(version = "2.7.5")
    val inverseArtifactV2 = artifact(version = "2.8.1")
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        directive to linkedSetOf(directArtifactV1, directArtifactV2, directArtifactV3),
        inversedDirective to linkedSetOf(inverseArtifactV1, inverseArtifactV2)
      ),
      inverseByDirective = mapOf(directive to linkedSetOf(inversedDirective))
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertThat(plans).containsExactly(
      MuzzleTaskPlan(directive, directArtifactV1),
      MuzzleTaskPlan(directive, directArtifactV2),
      MuzzleTaskPlan(directive, directArtifactV3),
      MuzzleTaskPlan(inversedDirective, inverseArtifactV1),
      MuzzleTaskPlan(inversedDirective, inverseArtifactV2),
    )
    assertThat(fakeService.resolveCalls)
      .withFailMessage("main directive + additional one for the inverse directive")
      .isEqualTo(2)
    assertThat(fakeService.inverseCalls).isEqualTo(1)
  }

  @Test
  fun `multiple artifacts with inverse creates comprehensive plan set`() {
    val directive = MuzzleDirective().apply {
      group = "io.netty"
      module = "netty-codec-http"
      versions = "[4.1.0,)"
      assertPass = true
      assertInverse = true
    }
    val inverseDirective = MuzzleDirective().apply {
      group = "io.netty"
      module = "netty-codec-http"
      versions = "[4.0.0,4.1.0)"
      assertPass = false
    }
    val passArtifacts = linkedSetOf(
      artifact("netty-codec-http", "4.1.0"),
      artifact("netty-codec-http", "4.1.50"),
      artifact("netty-codec-http", "4.2.0")
    )
    val failArtifacts = linkedSetOf(
      artifact("netty-codec-http", "4.0.30")
    )
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        directive to passArtifacts,
        inverseDirective to failArtifacts
      ),
      inverseByDirective = mapOf(
        directive to linkedSetOf(inverseDirective)
      )
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertThat(plans).withFailMessage("Should have 3 pass plans + 1 inverse fail plan").hasSize(4)
    assertThat(plans).containsExactly(
      MuzzleTaskPlan(directive, artifact("netty-codec-http", "4.1.0")),
      MuzzleTaskPlan(directive, artifact("netty-codec-http", "4.1.50")),
      MuzzleTaskPlan(directive, artifact("netty-codec-http", "4.2.0")),
      MuzzleTaskPlan(inverseDirective, artifact("netty-codec-http", "4.0.30")),
    )
    assertThat(fakeService.resolveCalls).isEqualTo(2)
    assertThat(fakeService.inverseCalls).isEqualTo(1)
  }

  @Test
  fun `mix of coreJdk and artifact directives`() {
    val coreJdkDirective = MuzzleDirective().apply {
      assertPass = true
      coreJdk()
    }
    val artifactDirective = MuzzleDirective().apply {
      group = "com.example"
      module = "demo"
      versions = "[1.0,2.0)"
      assertPass = true
    }
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        artifactDirective to linkedSetOf(artifact("demo", "1.5.0"))
      )
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(coreJdkDirective, artifactDirective))

    assertThat(plans).containsExactly(
      MuzzleTaskPlan(coreJdkDirective, null),
      MuzzleTaskPlan(artifactDirective, artifact("demo", "1.5.0")),
    )
    assertThat(fakeService.resolveCalls).isEqualTo(1)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `mix of pass and fail directives`() {
    val passDirective = MuzzleDirective().apply {
      group = "com.example"
      module = "demo"
      versions = "[2.0,)"
      assertPass = true
    }
    val failDirective = MuzzleDirective().apply {
      name = "before-2.0"
      group = "com.example"
      module = "demo"
      versions = "[,2.0)"
      assertPass = false
    }
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        passDirective to linkedSetOf(artifact("demo", "2.5.0"), artifact("demo", "3.0.0")),
        failDirective to linkedSetOf(artifact("demo", "1.5.0"))
      )
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(passDirective, failDirective))

    assertThat(plans).containsExactly(
      MuzzleTaskPlan(passDirective, artifact("demo", "2.5.0")),
      MuzzleTaskPlan(passDirective, artifact("demo", "3.0.0")),
      MuzzleTaskPlan(failDirective, artifact("demo", "1.5.0")),
    )
    assertThat(fakeService.resolveCalls).isEqualTo(2)
    assertThat(fakeService.inverseCalls).isEqualTo(0)
  }

  @Test
  fun `multiple directives with assertInverse`() {
    val directive1 = MuzzleDirective().apply {
      group = "com.example"
      module = "first"
      versions = "[3.0,)"
      assertPass = true
      assertInverse = true
    }
    val directive2 = MuzzleDirective().apply {
      group = "com.example"
      module = "second"
      versions = "[2.0,)"
      assertPass = true
      assertInverse = true
    }
    val inverse1 = MuzzleDirective().apply {
      group = "com.example"
      module = "first"
      versions = "[2.0,3.0)"
      assertPass = false
    }
    val inverse2 = MuzzleDirective().apply {
      group = "com.example"
      module = "second"
      versions = "[1.0,2.0)"
      assertPass = false
    }
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        directive1 to linkedSetOf(artifact("first", "3.5.0")),
        directive2 to linkedSetOf(artifact("second", "2.5.0")),
        inverse1 to linkedSetOf(artifact("first", "2.5.0")),
        inverse2 to linkedSetOf(artifact("second", "1.5.0"))
      ),
      inverseByDirective = mapOf(
        directive1 to linkedSetOf(inverse1),
        directive2 to linkedSetOf(inverse2)
      )
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive1, directive2))

    assertThat(plans).containsExactly(
      MuzzleTaskPlan(directive1, artifact("first", "3.5.0")),
      MuzzleTaskPlan(inverse1, artifact("first", "2.5.0")),
      MuzzleTaskPlan(directive2, artifact("second", "2.5.0")),
      MuzzleTaskPlan(inverse2, artifact("second", "1.5.0")),
    )
    assertThat(fakeService.resolveCalls).isEqualTo(4)
    assertThat(fakeService.inverseCalls).isEqualTo(2)
  }

  private fun artifact(module: String = "demo", version: String) =
    DefaultArtifact("com.example", module, "", "jar", version)

  private class FakeResolutionService(
    private val artifactsByDirective: Map<MuzzleDirective, Set<Artifact>> = emptyMap(),
    private val inverseByDirective: Map<MuzzleDirective, Set<MuzzleDirective>> = emptyMap(),
  ) : MuzzleResolutionService {
    var resolveCalls: Int = 0
      private set
    var inverseCalls: Int = 0
      private set

    override fun resolveArtifacts(directive: MuzzleDirective): Set<Artifact> {
      resolveCalls++
      return artifactsByDirective[directive].orEmpty()
    }

    override fun inverseOf(directive: MuzzleDirective): Set<MuzzleDirective> {
      inverseCalls++
      return inverseByDirective[directive].orEmpty()
    }
  }
}
