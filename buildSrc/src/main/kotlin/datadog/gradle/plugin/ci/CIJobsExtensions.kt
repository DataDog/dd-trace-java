package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import kotlin.math.abs

/** Parsed `-Pslot=X/Y` selection: 1-based [selected] slot out of [total] (> 0). */
private data class SlotSelection(val selected: Int, val total: Int) {
  /** Whether [key] falls into this slot. Bucket = `abs(key.hashCode() % total) + 1`, matching Java's truncating `%`. */
  fun selects(key: String): Boolean = abs(key.hashCode() % total) + 1 == selected
}

/** Boxes a parsed selection (possibly null) so "absent" can be cached distinctly from "not computed yet". */
private class SlotHolder(val selection: SlotSelection?)

private const val SLOT_HOLDER_KEY = "datadog.ci.slotSelection"
private const val FORCE_COVERAGE_PREFIXES_KEY = "datadog.ci.forceCoveragePrefixes"

/**
 * The `-Pslot=X/Y` selection, parsed once per build and cached on the root project, or null when the
 * property is absent, empty, or malformed — meaning no slot filtering (everything is selected).
 */
private val Project.slotSelection: SlotSelection?
  get() {
    val root = rootProject
    (root.extra.properties[SLOT_HOLDER_KEY] as? SlotHolder)?.let { return it.selection }
    val parsed = parseSlotSelection(root.providers.gradleProperty("slot").orNull, root)
    root.extra.set(SLOT_HOLDER_KEY, SlotHolder(parsed))
    return parsed
  }

private fun parseSlotSelection(raw: String?, root: Project): SlotSelection? {
  if (raw == null) return null
  val parts = raw.split("/")
  if (parts.size != 2) {
    root.logger.warn("Invalid slot format '{}', expected 'X/Y'. Treating all as selected.", raw)
    return null
  }
  // When CI_NODE_INDEX or CI_NODE_TOTAL is unset in non-parallel jobs, one part may be empty
  // (e.g. slot="/1") — treat as no filtering.
  if (parts[0].isBlank() || parts[1].isBlank()) return null
  val selected = parts[0].toIntOrNull()
  val total = parts[1].toIntOrNull()
  if (selected == null || total == null || total <= 0) {
    root.logger.warn("Invalid slot values '{}', expected numeric 'X/Y' with Y > 0. Treating all as selected.", raw)
    return null
  }
  return SlotSelection(selected, total)
}

/**
 * Module path prefixes registered (via [testAggregate] with `forceCoverage = true`) that always
 * collect coverage. Their test tasks must stay whole-project-slotted even without `-PcheckCoverage`.
 */
@Suppress("UNCHECKED_CAST")
private val Project.forceCoveragePrefixes: List<String>
  get() = (rootProject.extra.properties[FORCE_COVERAGE_PREFIXES_KEY] as? List<String>) ?: emptyList()

/**
 * Whether this module collects coverage — via `-PcheckCoverage` or a forceCoverage aggregate. When
 * true the module must run whole (project-level slotting) so per-module JaCoCo sees complete
 * execution data. [createRootTask] uses the same notion so the two stay consistent.
 */
private val Project.coverageEnabled: Boolean
  get() = rootProject.providers.gradleProperty("checkCoverage").isPresent ||
      forceCoveragePrefixes.any { path.startsWith(it) }

/**
 * Whether the current project is in the selected slot, at coarse one-task-per-project granularity.
 * Used by whole-project aggregates such as `runMuzzle`.
 */
val Project.isInSelectedSlot: Boolean
  get() = slotSelection?.selects(path) ?: true

/**
 * Whether this Test task is in the selected slot.
 *
 * Sharding at the *task* level lets a module's test variants — e.g. jdbc's
 * `test`/`forkedTest`/`oldH2Test`/`oldPostgresTest` — spread across different CI slots instead of
 * serializing in one job. The key is `"<projectPath>:<taskName>"`.
 *
 * Exception: when the module collects coverage (see [coverageEnabled]) all its test tasks must stay
 * in one slot so per-module JaCoCo sees complete execution data, so we fall back to the project-level
 * key. This MUST match the coverage decision in [createRootTask].
 */
val Task.isInSelectedSlot: Boolean
  get() {
    val slot = project.slotSelection ?: return true
    val key = if (project.coverageEnabled) project.path else "${project.path}:$name"
    return slot.selects(key)
  }

/**
 * Returns the task's path, given affected projects, if this task or its dependencies are affected by git changes.
 */
internal fun findAffectedTaskPath(baseTask: Task, affectedProjects: Map<Project, Set<String>>): String? {
  val visited = mutableSetOf<Task>()
  val queue = mutableListOf(baseTask)

  while (queue.isNotEmpty()) {
    val t = queue.removeAt(0)
    if (visited.contains(t)) {
      continue
    }
    visited.add(t)

    val affectedTasks = affectedProjects[t.project]
    if (affectedTasks != null) {
      if (affectedTasks.contains("all")) {
        return "${t.project.path}:${t.name}"
      }
      if (affectedTasks.contains(t.name)) {
        return "${t.project.path}:${t.name}"
      }
    }

    t.taskDependencies.getDependencies(t).forEach { queue.add(it) }
  }
  return null
}

/**
 * Creates a single aggregate root task that depends on matching subproject tasks.
 *
 * When [testTaskFilter] is non-null and coverage is off, the aggregate shards at *task* granularity:
 * it depends only on the in-slot Test tasks the umbrella would run, so out-of-slot modules aren't
 * pulled into this slot at all (their compile/test work does not run here).
 *
 * Otherwise — the `check` aggregate, or any coverage build — it shards at *project* granularity.
 * `onlyIf` on a Test task only skips that task's own action, never its dependencies or non-Test
 * siblings (spotbugs, forbiddenApis, codenarc, jacoco...), and JaCoCo needs a module's complete
 * execution data, so the whole module must stay in one slot.
 */
private fun Project.createRootTask(
  rootTaskName: String,
  subProjTaskName: String,
  includePrefixes: List<String>,
  excludePrefixes: List<String>,
  forceCoverage: Boolean,
  testTaskFilter: ((Test) -> Boolean)?
) {
  val coverage = forceCoverage || rootProject.providers.gradleProperty("checkCoverage").isPresent
  val perTaskShardable = !coverage && testTaskFilter != null
  val slot = slotSelection
  tasks.register(rootTaskName) {
    subprojects.forEach forEachSub@{ subproject ->
      if (
        !includePrefixes.any { subproject.path.startsWith(it) } ||
        excludePrefixes.any { subproject.path.startsWith(it) }
      ) {
        return@forEachSub
      }

      val subProjTask = subproject.tasks.findByName(subProjTaskName)

      // Git-change filtering, keyed off the umbrella task at module granularity (unchanged behavior).
      if (subProjTask != null && rootProject.extra.get("useGitChanges") as Boolean) {
        @Suppress("UNCHECKED_CAST")
        val affectedProjects = rootProject.extra.get("affectedProjects") as Map<Project, Set<String>>
        val affectedTaskPath = findAffectedTaskPath(subProjTask, affectedProjects)
        if (affectedTaskPath == null) {
          logger.warn("Skipping ${subproject.path}:$subProjTaskName (not affected by changed files)")
          return@forEachSub
        }
        logger.warn("Selecting ${subproject.path}:$subProjTaskName (affected by $affectedTaskPath)")
      }

      if (perTaskShardable) {
        // Depend only on the in-slot Test tasks the umbrella would run; leave the rest to other slots.
        subproject.tasks.withType(Test::class.java).matching { testTaskFilter!!(it) }.forEach { testTask ->
          if (slot == null || slot.selects("${subproject.path}:${testTask.name}")) {
            dependsOn(testTask)
          }
        }
      } else if (subProjTask != null && (slot == null || slot.selects(subproject.path))) {
        dependsOn(subProjTask)
        if (coverage) {
          subproject.tasks.findByName("jacocoTestReport")?.let { dependsOn(it) }
          subproject.tasks.findByName("jacocoTestCoverageVerification")?.let { dependsOn(it) }
        }
      }
    }
  }
}

/**
 * Creates aggregate test tasks for CI using createRootTask() above
 *
 * Creates three subtasks for the given base task name:
 * - ${baseTaskName}Test - runs allTests
 * - ${baseTaskName}LatestDepTest - runs allLatestDepTests
 * - ${baseTaskName}Check - runs check
 */
fun Project.testAggregate(
  baseTaskName: String,
  includePrefixes: List<String>,
  excludePrefixes: List<String> = emptyList(),
  forceCoverage: Boolean = false
) {
  if (forceCoverage) {
    registerForceCoveragePrefixes(includePrefixes)
  }
  // The two Test umbrellas mirror the membership filters in dd-trace-java.configure-tests.gradle.kts.
  createRootTask("${baseTaskName}Test", "allTests", includePrefixes, excludePrefixes, forceCoverage) {
    !it.name.contains("latest", ignoreCase = true) && it.name != "traceAgentTest"
  }
  createRootTask("${baseTaskName}LatestDepTest", "allLatestDepTests", includePrefixes, excludePrefixes, forceCoverage) {
    it.name.contains("latest", ignoreCase = true)
  }
  createRootTask("${baseTaskName}Check", "check", includePrefixes, excludePrefixes, forceCoverage, testTaskFilter = null)
}

@Suppress("UNCHECKED_CAST")
private fun Project.registerForceCoveragePrefixes(prefixes: List<String>) {
  val existing = rootProject.extra.properties[FORCE_COVERAGE_PREFIXES_KEY] as? MutableList<String>
  if (existing != null) {
    existing.addAll(prefixes)
  } else {
    rootProject.extra.set(FORCE_COVERAGE_PREFIXES_KEY, prefixes.toMutableList())
  }
}
