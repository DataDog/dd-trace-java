package datadog.gradle.plugin.muzzle

import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider

/**
 * Registers validation tasks when Gradle resolves a module's muzzle finalizers.
 *
 * [tasks] is a plain [Provider] used as dependency notation by [Task.finalizedBy]. During normal
 * task-graph construction, Gradle resolves its task-set value after project evaluation and before
 * execution. Registration therefore runs outside task configuration actions and can use completed
 * directives to discover checks whose names were unknown when the plugin was applied.
 *
 * A [TaskProvider] references an already registered task. A task-backed [Provider.map] can supply
 * producer dependencies without evaluating its mapping, so registration must not be a side effect
 * of that mapping. [ProviderFactory.provider] supplies the task-set value directly instead.
 *
 * Providers can be queried repeatedly. Cache both success and failure to avoid duplicate task
 * registration, including retries after partial registration.
 *
 * Note that [org.gradle.api.tasks.TaskDependency], a mechanism to supplying directly the **concrete**
 * tasks when assembling the execution graph, exists today but is on the side-line possibly
 * getting deprecated for removal.
 *
 * shortcut: generated-check CLI selectors remain unsupported; revisit if individual checks
 * need to be selected or excluded.
 *
 * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/provider/ProviderFactory.html#provider(java.util.concurrent.Callable)">ProviderFactory.provider API</a>
 * @see <a href="https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskProvider.html">TaskProvider API</a>
 * @see <a href="https://github.com/gradle/gradle/issues/30534#issuecomment-2360291115">Discussion on removing older Gradle interface API</a>
 */
internal class MuzzleValidationTasks(
  providerFactory: ProviderFactory,
  registerValidationTasks: () -> Set<Task>
) {
  private val registration by lazy { runCatching(registerValidationTasks) }

  val tasks: Provider<Set<Task>> = providerFactory.provider { registration.getOrThrow() }
}
