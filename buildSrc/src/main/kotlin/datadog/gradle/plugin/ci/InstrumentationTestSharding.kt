package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.file.FileTreeElement
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import java.io.Serializable

internal val Project.isInstrumentationProject: Boolean
  get() = path.startsWith(InstrumentationTestPlan.INSTRUMENTATION_PREFIX)

internal val Test.isMainInstrumentationTest: Boolean
  get() = project.isInstrumentationProject && !name.contains("latest", ignoreCase = true) && name != "traceAgentTest"

internal val Project.instrumentationSlot: TestSlot?
  get() = TestSlot.parse(rootProject.providers.gradleProperty("slot").orNull)

internal fun Project.instrumentationTestPlan(totalSlots: Int): InstrumentationTestPlan {
  val extra = rootProject.extra
  val key = "instrumentationTestPlan.$totalSlots"
  synchronized(extra) {
    if (!extra.has(key)) {
      val projects = rootProject.allprojects.filter { it.isInstrumentationProject && "allTests" in it.tasks.names }
      val durationsFile = rootProject.providers.gradleProperty("instrumentationTestDurationsFile").orNull
      val measured = if (durationsFile == null) {
        InstrumentationTestPlan.loadDefaultDurations()
      } else {
        InstrumentationTestPlan.loadDurations(rootProject.file(durationsFile).readText())
      }
      val durations = measured.filterKeys { path ->
        val project = rootProject.findProject(path.substringBeforeLast(':'))
        project != null && path.substringAfterLast(':') in project.tasks.names
      }
      extra.set(key, InstrumentationTestPlan.create(projects.map { it.path }, durations, totalSlots))
    }
    return extra.get(key) as InstrumentationTestPlan
  }
}

/** Other test categories and latest dependency tests retain their project-based slot assignment. */
val Test.isInSelectedTestSlot: Provider<Boolean>
  get() = project.provider {
    val slot = project.instrumentationSlot
    if (isMainInstrumentationTest) {
      slot == null || project.instrumentationTestPlan(slot.total).selectsTask(path, slot.index)
    } else {
      project.isInSelectedSlot.get()
    }
  }

/** Filter dependencies before Gradle schedules their compilation and runtime classpaths. */
val Test.includeInInstrumentationAggregate: Boolean
  get() = !project.isInstrumentationProject || isInSelectedTestSlot.get()

/** Attach class restrictions after module-specific includes/excludes have been configured. */
fun configureInstrumentationTestSharding(test: Test) {
  if (!test.isMainInstrumentationTest) return
  val slot = test.project.instrumentationSlot ?: return
  val selection = test.project.instrumentationTestPlan(slot.total).classSelection(test.path) ?: return
  test.exclude(ClassShardExclusion(selection, slot.index))
  test.inputs.property("instrumentationClassShard", selection.fingerprint(slot.index))
  // A stale score can refer to a deleted class, leaving its former shard empty.
  test.failOnNoDiscoveredTests.set(false)
}

internal class ClassShardExclusion(
  private val selection: TestClassSelection,
  private val slot: Int
) : Spec<FileTreeElement>,
  Serializable {
  override fun isSatisfiedBy(element: FileTreeElement): Boolean {
    if (element.isDirectory || !element.path.endsWith(".class")) return false
    val className = element.path.removeSuffix(".class").replace('/', '.')
    return selection.slotFor(className) != slot
  }
}
