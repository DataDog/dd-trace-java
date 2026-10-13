package datadog.gradle.plugin.ci

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import kotlin.math.abs

class InstrumentationTestPlanTest {
  private val prefix = InstrumentationTestPlan.INSTRUMENTATION_PREFIX

  @Test
  fun `slot parsing fails open for incomplete and invalid values`() {
    listOf(null, "", "/1", "1/", "x/8", "0/8", "9/8", "1/0", "1/8/2").forEach {
      assertThat(TestSlot.parse(it)).describedAs("slot %s", it).isNull()
    }
    assertThat(TestSlot.parse("3/8")).isEqualTo(TestSlot(3, 8))
  }

  @Test
  fun `small projects stay together and unknown tasks retain an owner`() {
    val project = "${prefix}small"
    val plan = InstrumentationTestPlan.create(listOf(project), mapOf("$project:test" to mapOf("SmallTest" to 30.0)), 8)
    val owner = plan.projectSlots.getValue(project)
    assertThat(plan.slotsForTask("$project:test")).containsExactly(owner)
    assertThat(plan.slotsForTask("$project:newTest")).containsExactly(owner)
    assertThat(plan.classSelection("$project:test")).isNull()
  }

  @Test
  fun `heavy projects split by existing tasks before splitting classes`() {
    val project = "${prefix}versions"
    val durations = (1..4).associate { "$project:version${it}Test" to mapOf("VersionTest" to 100.0) }
    val plan = InstrumentationTestPlan.create(listOf(project), durations, 8)
    assertThat(durations.keys.flatMap { plan.slotsForTask(it) }.distinct()).hasSize(4)
    durations.keys.forEach { assertThat(plan.classSelection(it)).isNull() }
  }

  @Test
  fun `class groups are exhaustive disjoint and include future classes exactly once`() {
    val project = "${prefix}heavy"
    val task = "$project:test"
    val classes = (1..8).associate { "example.Test$it" to 60.0 }
    val plan = InstrumentationTestPlan.create(listOf(project), mapOf(task to classes), 8)
    val selection = plan.classSelection(task)!!
    assertThat(plan.slotsForTask(task)).hasSize(4)
    (classes.keys + "example.NewTest").forEach { className ->
      val slots = (1..8).filter { plan.selectsTask(task, it) && selection.slotFor(className) == it }
      assertThat(slots).describedAs(className).hasSize(1)
    }
    assertThat(selection.classSlots.keys).containsExactlyInAnyOrderElementsOf(classes.keys)
  }

  @Test
  fun `nested and anonymous classes follow the enclosing class`() {
    val task = "${prefix}nested:test"
    val plan = InstrumentationTestPlan.create(listOf("${prefix}nested"), mapOf(task to mapOf("example.Outer" to 150.0, "example.Outer\$Nested" to 150.0, "example.Other" to 150.0)), 8)
    val selection = plan.classSelection(task)!!
    assertThat(selection.slotFor("example.Outer\$Nested")).isEqualTo(selection.slotFor("example.Outer"))
    assertThat(selection.slotFor("example.Outer\$1")).isEqualTo(selection.slotFor("example.Outer"))
  }

  @Test
  fun `project fanout is bounded even with many version and forked tasks`() {
    val projects = (1..10).map { "${prefix}project$it" }
    val durations = projects.flatMap { project ->
      (1..10).map { "$project:version${it}Test" to (1..8).associate { "Test$it" to 60.0 } }
    }.toMap()
    val plan = InstrumentationTestPlan.create(projects, durations, 8)
    projects.forEach { project ->
      val slots = durations.keys.filter { it.startsWith("$project:") }.flatMap { plan.slotsForTask(it) } + plan.projectSlots.getValue(project)
      assertThat(slots.distinct()).hasSizeLessThanOrEqualTo(4)
    }
    assertThat(plan.slotWeights.max() / plan.slotWeights.min()).isLessThan(1.1)
  }

  @Test
  fun `assignment is independent of project task and class enumeration order`() {
    val projects = (1..12).map { "${prefix}project$it" }
    val durations = projects.associate { "$it:test" to mapOf("A" to 100.013, "B" to 80.007, "C" to 40.003) }
    val first = InstrumentationTestPlan.create(projects, durations, 8)
    val second = InstrumentationTestPlan.create(projects.reversed(), durations.entries.reversed().associate { it.key to it.value.entries.reversed().associate { it.toPair() } }, 8)
    assertThat(first.projectSlots).isEqualTo(second.projectSlots)
    assertThat(first.slotWeights).isEqualTo(second.slotWeights)
    durations.keys.forEach {
      assertThat(first.slotsForTask(it)).isEqualTo(second.slotsForTask(it))
      assertThat(first.classSelection(it)).isEqualTo(second.classSelection(it))
    }
  }

  @Test
  fun `one slot runs complete tasks without class filtering`() {
    val project = "${prefix}heavy"
    val plan = InstrumentationTestPlan.create(listOf(project), mapOf("$project:test" to mapOf("A" to 300.0, "B" to 300.0)), 1)
    assertThat(plan.slotsForTask("$project:test")).containsExactly(1)
    assertThat(plan.classSelection("$project:test")).isNull()
  }

  @Test
  fun `durations reject other products and invalid scores`() {
    assertThatIllegalArgumentException().isThrownBy { InstrumentationTestPlan.loadDurations("""{"tasks":{":dd-smoke-tests:test":{"A":10}}}""") }
    assertThatIllegalArgumentException().isThrownBy { InstrumentationTestPlan.loadDurations("""{"tasks":{"${prefix}example:test":{"A":-1}}}""") }
    assertThatIllegalArgumentException().isThrownBy { InstrumentationTestPlan.create(listOf(":dd-trace-core"), emptyMap(), 8) }
    assertThatIllegalArgumentException().isThrownBy { InstrumentationTestPlan.create(emptyList(), emptyMap(), 0) }
  }

  @Test
  fun `bundled CI scores improve the modeled eight slot balance`() {
    val durations = InstrumentationTestPlan.loadDefaultDurations()
    val projects = durations.keys.map { it.substringBeforeLast(':') }.distinct()
    val plan = InstrumentationTestPlan.create(projects, durations, 8)
    val previousLoads = DoubleArray(8)
    projects.forEach { project ->
      previousLoads[abs(project.hashCode() % 8)] += durations.filterKeys { it.substringBeforeLast(':') == project }.values.sumOf { it.values.sum() }
    }
    assertThat(durations).hasSizeGreaterThan(400)
    assertThat(plan.slotWeights.max() / plan.slotWeights.min()).isLessThan(1.1)
    assertThat(plan.slotWeights.max()).isLessThan(previousLoads.max())
    println("Modeled instrumentation seconds: hash=${previousLoads.toList()}, duration=${plan.slotWeights}")
    durations.keys.forEach { task ->
      assertThat(plan.slotsForTask(task)).isNotEmpty()
      assertThat(plan.slotsForTask(task)).allSatisfy { slot -> assertThat(plan.selectsProject(task.substringBeforeLast(':'), slot)).isTrue() }
    }
  }
}
