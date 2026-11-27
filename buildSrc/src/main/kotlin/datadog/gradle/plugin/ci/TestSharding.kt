package datadog.gradle.plugin.ci

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import java.util.ArrayDeque
import java.util.concurrent.Callable
import kotlin.math.abs

private const val TEST_SUITE = "ciTestSuite"

private val logger = Logging.getLogger(TestSharding::class.java)

/** Whether a project's `allTests` aggregate runs the Test task named [name]. */
internal fun isAllTestsTask(name: String): Boolean = !name.contains("latest", ignoreCase = true) && name != "traceAgentTest"

/** Whether a project's `allLatestDepTests` aggregate runs the Test task named [name]. */
internal fun isAllLatestDepTestsTask(name: String): Boolean = name.contains("latest", ignoreCase = true)

/** Name of the companion task that runs the `*ForkedTest` classes of [suiteName]. */
internal fun forkedTestTaskName(suiteName: String): String = if (suiteName == "test") "forkedTest" else "${suiteName.removeSuffix("Test")}ForkedTest"

/** Associates a forked Test task with the suite whose classes it runs. */
fun Test.useSuiteShard(suiteName: String) {
  extra.set(TEST_SUITE, suiteName)
}

internal val Test.suiteName: String
  get() = if (extra.has(TEST_SUITE)) extra.get(TEST_SUITE) as String else name

/**
 * How a root aggregate distributes one kind of project aggregate (`allTests` or `allLatestDepTests`)
 * by suite.
 *
 * @property runs selects, by name, the Test tasks the project aggregate runs
 * @property defaultSuite the suite whose group keeps the project slot
 */
internal class SuiteSharding(val runs: (String) -> Boolean, val defaultSuite: String) {
  private val defaultTasks = setOf(defaultSuite, forkedTestTaskName(defaultSuite))

  /** Whether the project aggregate runs a suite besides the default one, judged by task name only. */
  fun hasExtraSuite(testTaskNames: Collection<String>): Boolean = testTaskNames.any { runs(it) && it !in defaultTasks }
}

/** Parsed 1-based CI slot selection (`selected/total`). */
internal data class Slot(val selected: Int, val total: Int) {
  fun slotFor(key: String): Int = abs(key.hashCode() % total) + 1

  fun selects(key: String, subject: String): Boolean {
    val assignedSlot = slotFor(key)
    logger.info("{} assigned to slot {}/{}, active slot is {}", subject, assignedSlot, total, selected)
    return assignedSlot == selected
  }
}

/**
 * Parses a `"X/Y"` slot specification (1-based). Returns `null` when the value is incomplete or
 * invalid so callers fall back to selecting everything.
 */
internal fun parseSlot(slot: String): Slot? {
  val parts = slot.split("/")
  if (parts.size != 2) {
    logger.warn("Invalid slot format '{}', expected 'X/Y'. Treating all projects as selected.", slot)
    return null
  }

  // When CI_NODE_INDEX or CI_NODE_TOTAL is unset in non-parallel jobs, one part may be empty (e.g. slot="/1") — treat as no filtering
  if (parts[0].isBlank() || parts[1].isBlank()) {
    logger.info("Incomplete slot value '{}', CI_NODE_INDEX or CI_NODE_TOTAL not set. Treating all projects as selected.", slot)
    return null
  }

  val selectedSlot = parts[0].toIntOrNull()
  val totalSlots = parts[1].toIntOrNull()

  if (selectedSlot == null || totalSlots == null || totalSlots <= 0) {
    logger.warn("Invalid slot values '{}', expected numeric 'X/Y' with Y > 0. Treating all projects as selected.", slot)
    return null
  }

  return Slot(selectedSlot, totalSlots)
}

/**
 * Suite placement state shared by the root aggregates of one build.
 *
 * Root aggregates record their groups while they are configured. Only the aggregates that end up
 * in the task graph count: [selectedTests] is set once the graph is ready, and has no value before.
 */
internal class TestSharding(val slot: Slot?, objects: ObjectFactory) {
  /** Paths of the projects applying JaCoCo, which keep project placement for their coverage data. */
  val jacocoProjects = mutableSetOf<String>()

  /** Test paths that run outside their project slot on this runner. */
  val selectedTests: SetProperty<String> = objects.setProperty(String::class.java).apply { finalizeValueOnRead() }

  private val selectedByAggregate = mutableMapOf<String, MutableSet<String>>()
  private val groupKeysByAggregate = mutableMapOf<String, MutableMap<String, String>>()

  fun recordGroup(aggregatePath: String, key: String, group: Set<Test>, selected: Boolean) {
    val groupKeys = groupKeysByAggregate.getOrPut(aggregatePath) { mutableMapOf() }
    group.forEach { groupKeys[it.path] = key }
    if (selected) {
      selectedByAggregate.getOrPut(aggregatePath) { mutableSetOf() }.addAll(group.map { it.path })
    }
  }

  fun onTaskGraphReady(graph: TaskExecutionGraph) {
    selectedTests.set(selectedByAggregate.filterKeys(graph::hasTask).values.flatten())
    val groupKeys = groupKeysByAggregate.filterKeys(graph::hasTask).values
    if (groupKeys.isNotEmpty()) {
      checkSuiteLinks(graph, groupKeys)
    }
  }

  /**
   * Fails when a suite-placed test reaches a test of another group in its project, through a
   * dependency grouping could not see: an inferred one, such as an input wired to another test's
   * output. Gradle would schedule the other test on this runner while its slot skips it.
   */
  private fun checkSuiteLinks(graph: TaskExecutionGraph, groupKeys: Collection<Map<String, String>>) {
    val testsReached = mutableMapOf<Task, Set<Test>>()
    fun linkedTests(task: Task): Set<Test> {
      val tests = linkedSetOf<Test>()
      for (next in graph.getDependencies(task) + task.finalizedBy.getDependencies(task)) {
        if (next is Test) {
          tests.add(next)
        } else {
          tests.addAll(testsReachedThrough(next, testsReached, ::linkedTests))
        }
      }
      return tests
    }

    val problems = sortedSetOf<String>()
    for (keys in groupKeys) {
      for (test in graph.allTasks.filterIsInstance<Test>()) {
        val key = keys[test.path] ?: continue
        linkedTests(test)
          .filter { it.project == test.project && keys[it.path] != key }
          .forEach { problems.add("${test.path} -> ${it.path}") }
      }
    }
    if (problems.isNotEmpty()) {
      throw GradleException(
        "These Test tasks depend on tests that -Pslot places on other runners:\n" +
          problems.joinToString("\n") { "  $it" } +
          "\nDeclare the link with dependsOn or finalizedBy so both tests share a slot.",
      )
    }
  }
}

/** Memoized walk through non-Test tasks; a cycle contributes nothing. */
private fun testsReachedThrough(task: Task, memo: MutableMap<Task, Set<Test>>, linkedTests: (Task) -> Set<Test>): Set<Test> = memo[task] ?: run {
  memo[task] = emptySet()
  linkedTests(task).also { memo[task] = it }
}

/** Registers the [TestSharding] extension on the root project and its task graph hook. */
internal fun Project.registerTestSharding(): TestSharding {
  val slot = providers.gradleProperty("slot").orNull?.let(::parseSlot)
  val sharding = TestSharding(slot, objects)
  extensions.add(TestSharding::class.java, "testSharding", sharding)
  gradle.taskGraph.whenReady { sharding.onTaskGraphReady(this) }
  return sharding
}

/**
 * Groups the aggregate's tests so each group can run on one runner. A group holds:
 * - the tasks of one suite, such as a suite and its forked companion;
 * - tests linked by explicit dependencies or finalizers, so Gradle can enforce the link and run each test once;
 * - tests sharing an explicit setup task of the project, such as an application build, so it runs on one runner.
 *
 * Inferred dependencies are not read here, to avoid resolving test classpaths on every runner. They
 * are checked once the task graph is ready, by [TestSharding.onTaskGraphReady].
 *
 * Return null to keep the original project placement when the aggregate has its own work, has
 * non-Test children, or reaches a Test in a different project.
 */
internal fun Task.testShardGroups(children: Set<Task>): Collection<Set<Test>>? {
  if (actions.isNotEmpty() || finalizedBy.getDependencies(this).isNotEmpty()) {
    return null
  }
  if (children.any { it !is Test || it.project != project }) {
    return null
  }

  val buildTaskNames = project.sourceSetBuildTaskNames()
  val groups = DisjointSets<Test>()
  val testBySuite = mutableMapOf<String, Test>()
  val testBySetupTask = mutableMapOf<Task, Test>()
  val relatedTasks = mutableMapOf<Task, Set<Task>>()
  val visited = mutableSetOf<Test>()
  val pendingTests = ArrayDeque(children.filterIsInstance<Test>())
  while (pendingTests.isNotEmpty()) {
    val test = pendingTests.removeFirst()
    if (!visited.add(test)) {
      continue
    }
    groups.add(test)
    testBySuite.putIfAbsent(test.suiteName, test)?.let { groups.union(test, it) }

    val seen = mutableSetOf<Task>(test)
    val pending = ArrayDeque(relatedTasks.getOrPut(test) { test.explicitlyRelatedTasks() })
    while (pending.isNotEmpty()) {
      val task = pending.removeFirst()
      if (!seen.add(task)) {
        continue
      }
      if (task is Test) {
        if (task.project != project) {
          return null
        }
        groups.add(task)
        groups.union(test, task)
        pendingTests.add(task)
        continue
      }
      if (task.project == project && task.name !in buildTaskNames) {
        testBySetupTask.putIfAbsent(task, test)?.let { groups.union(test, it) }
      }
      pending.addAll(relatedTasks.getOrPut(task) { task.explicitlyRelatedTasks() })
    }
  }
  return groups.sets()
}

/** Compile, resources and classes tasks: shared by suites through the build cache, not setup work. */
private fun Project.sourceSetBuildTaskNames(): Set<String> = extensions.findByType(SourceSetContainer::class.java).orEmpty().flatMapTo(mutableSetOf()) { sourceSet ->
  listOf("java", "groovy", "scala", "kotlin").map { sourceSet.getCompileTaskName(it) } +
    sourceSet.classesTaskName +
    sourceSet.processResourcesTaskName
}

/**
 * Tasks this task finalizes on or explicitly depends on. Only task references are followed: other
 * `dependsOn` items, such as a configuration, are inferred dependencies and are left unresolved.
 */
private fun Task.explicitlyRelatedTasks(): Set<Task> {
  val tasks = linkedSetOf<Task>()
  tasks.addAll(finalizedBy.getDependencies(this))
  fun add(item: Any?) {
    when (item) {
      is Task -> tasks.add(item)
      is CharSequence -> project.tasks.findByPath(item.toString())?.let { tasks.add(it) }
      is Provider<*> -> add(item.orNull)
      is Callable<*> -> add(item.call())
      is Iterable<*> -> item.forEach(::add)
      is Array<*> -> item.forEach(::add)
    }
  }
  dependsOn.forEach(::add)
  return tasks
}

/** Union-find over identity-compared items. */
private class DisjointSets<T : Any> {
  private val parent = linkedMapOf<T, T>()

  fun add(item: T) {
    parent.putIfAbsent(item, item)
  }

  fun union(a: T, b: T) {
    val rootA = find(a)
    val rootB = find(b)
    if (rootA != rootB) {
      parent[rootB] = rootA
    }
  }

  fun sets(): Collection<Set<T>> = parent.keys.groupBy(::find).values.map { it.toSet() }

  private fun find(item: T): T {
    var root = item
    while (parent.getValue(root) != root) {
      root = parent.getValue(root)
    }
    var current = item
    while (current != root) {
      val next = parent.getValue(current)
      parent[current] = root
      current = next
    }
    return root
  }
}
