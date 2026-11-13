package datadog.gradle.plugin.ci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CIJobsExtensionsTest {
  private val lettuce = ":dd-java-agent:instrumentation:lettuce:lettuce-5.0"
  private val jdbc = ":dd-java-agent:instrumentation:jdbc"

  @Test
  fun `project slot is the hash of the project path`() {
    assertEquals(6, projectSlot(lettuce, 8))
    assertEquals(5, projectSlot(jdbc, 8))
  }

  @Test
  fun `unlisted test tasks stay in their project slot`() {
    assertEquals(6, testTaskSlot("$lettuce:test", 8))
    assertEquals(6, testTaskSlot("$lettuce:lettuce51Test", 8))
    assertEquals(5, testTaskSlot("$jdbc:forkedTest", 8))
  }

  @Test
  fun `listed test tasks move to their override slot`() {
    assertEquals(3, testTaskSlot("$lettuce:lettuce60Test", 8))
    assertEquals(4, testTaskSlot("$lettuce:lettuce62Test", 8))
    assertEquals(8, testTaskSlot("$jdbc:oldPostgresTest", 8))
    assertEquals(setOf(6, 3, 4), projectSlots(lettuce, 8))
    assertEquals(setOf(5, 8), projectSlots(jdbc, 8))
  }

  @Test
  fun `overrides only apply to their total slot count`() {
    for (total in listOf(4, 6, 12)) {
      assertEquals(projectSlot(lettuce, total), testTaskSlot("$lettuce:lettuce62Test", total))
      assertEquals(setOf(projectSlot(lettuce, total)), projectSlots(lettuce, total))
    }
  }

  @Test
  fun `every override moves a task to another valid slot of its project`() {
    for ((total, overrides) in TEST_TASK_SLOT_OVERRIDES) {
      for ((taskPath, slot) in overrides) {
        val projectPath = taskPath.substringBeforeLast(':')
        assertTrue(slot in 1..total, "$taskPath slot $slot is not in 1..$total")
        assertNotEquals(projectSlot(projectPath, total), slot, "$taskPath already runs in slot $slot")
        assertTrue(slot in projectSlots(projectPath, total), "$projectPath is not selected in slot $slot")
      }
    }
  }

  @Test
  fun `overrides do not leak to projects sharing a path prefix`() {
    val parent = ":dd-java-agent:instrumentation:lettuce"
    assertEquals(setOf(projectSlot(parent, 8)), projectSlots(parent, 8))
  }
}
