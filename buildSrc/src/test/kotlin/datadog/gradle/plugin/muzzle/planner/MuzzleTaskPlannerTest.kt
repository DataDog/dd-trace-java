package datadog.gradle.plugin.muzzle.planner

import datadog.gradle.plugin.muzzle.MuzzleDirective
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MuzzleTaskPlannerTest {

  @Test
  fun `coreJdk directive does not call resolution service`() {
    val directive = MuzzleDirective().apply {
      assertPass = true
      coreJdk()
    }
    val fakeService = FakeResolutionService()

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))
    val expectedPlans = listOf(
      MuzzleTaskPlan(directive, null)
    )

    assertEquals(expectedPlans, plans)
    assertEquals(0, fakeService.resolveCalls)
    assertEquals(0, fakeService.inverseCalls)
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
      artifact("1.0.0"),
      artifact("1.1.0"),
      artifact("1.2.0"),
      artifact("1.3.0")
    )
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(directive to artifacts)
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertEquals(
      listOf(
        MuzzleTaskPlan(directive, artifact("1.0.0")),
        MuzzleTaskPlan(directive, artifact("1.1.0")),
        MuzzleTaskPlan(directive, artifact("1.2.0")),
        MuzzleTaskPlan(directive, artifact("1.3.0")),
      ),
      plans
    )
    assertEquals(1, fakeService.resolveCalls)
    assertEquals(0, fakeService.inverseCalls)
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
    val directArtifactV1 = artifact("3.12.13")
    val directArtifactV2 = artifact("4.4.1")
    val directArtifactV3 = artifact("5.3.2")
    val inverseArtifactV1 = artifact("2.7.5")
    val inverseArtifactV2 = artifact("2.8.1")
    val fakeService = FakeResolutionService(
      artifactsByDirective = mapOf(
        directive to linkedSetOf(directArtifactV1, directArtifactV2, directArtifactV3),
        inversedDirective to linkedSetOf(inverseArtifactV1, inverseArtifactV2)
      ),
      inverseByDirective = mapOf(directive to linkedSetOf(inversedDirective))
    )

    val plans = MuzzleTaskPlanner(fakeService).plan(listOf(directive))

    assertEquals(
      listOf(
        MuzzleTaskPlan(directive, directArtifactV1),
        MuzzleTaskPlan(directive, directArtifactV2),
        MuzzleTaskPlan(directive, directArtifactV3),
        MuzzleTaskPlan(inversedDirective, inverseArtifactV1),
        MuzzleTaskPlan(inversedDirective, inverseArtifactV2),
      ), plans)
    assertEquals(2, fakeService.resolveCalls, "main directive + additional one for the inverse directive")
    assertEquals(1, fakeService.inverseCalls)
  }

  private fun artifact(version: String) =
    DefaultArtifact("com.example", "demo", "", "jar", version)

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
