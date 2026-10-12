package datadog.gradle.plugin.muzzle

import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider

/**
 * Supplies the validation tasks registered when Gradle resolves a module's muzzle finalizers.
 *
 * During normal task-graph construction, Gradle queries [TaskDependency] after project evaluation
 * and before task execution. The registration block can therefore use the completed directives to
 * discover tasks whose names were not known when the plugin was applied.
 *
 * **Difference with a [TaskProvider] and [Provider]**
 *
 * * A [TaskProvider] is a special [Provider] that references a named task registration.
 * * While a plain [Provider] can defer the same work, but represents a value and may expose
 *   producer dependencies without evaluating that value.
 * * A [TaskDependency] directly supplies the **concrete** tasks in this graph relationship,
 *   so registration happens outside task configuration, however it does so only when the execution
 *   graph needs to be computed.
 *
 * This implementation caches the result of [registerValidationTasks] whether it is successful or failed.
 * This is necessary to avoid performing twice task registration.
 *
 * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskDependency.html">TaskDependency API</a>
 * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html">Provider API</a>
 * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskProvider.html">TaskProvider API</a>
 */
internal class MuzzleValidationTasks(registerValidationTasks: () -> Set<Task>) : TaskDependency {
  private val registration by lazy { runCatching(registerValidationTasks) }

  override fun getDependencies(task: Task?): Set<Task> = registration.getOrThrow()
}
