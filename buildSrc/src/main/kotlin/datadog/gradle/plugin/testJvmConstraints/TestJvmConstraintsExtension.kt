package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

abstract class TestJvmConstraintsExtension @Inject constructor(
  private val taskName: String,
  private val objects: ObjectFactory,
  private val providers: ProviderFactory,
  private val project: Project,
) {
  val minJavaVersionForTests = objects.property<JavaVersion>()
    .convention(
      providers.provider { project.extra["${taskName}MinJavaVersionForTests"] as JavaVersion }.orElse(
        providers.provider { project.extra["minJavaVersionForTests"] as? JavaVersion }
      )
    )
  val maxJavaVersionForTests = objects.property<JavaVersion>()
    .convention(
      providers.provider { project.extra["${taskName}MaxJavaVersionForTests"] as JavaVersion }.orElse(
        providers.provider { project.extra["maxJavaVersionForTests"] as? JavaVersion }
      )
    )
  val forceJdk = objects.listProperty<String>().convention(emptyList())
    .convention(providers.provider {
      @Suppress("UNCHECKED_CAST")
      project.extra["forceJdk"] as? List<String> ?: emptyList()
    })
  val excludeJdk = objects.listProperty<String>().convention(emptyList())
    .convention(providers.provider {
      @Suppress("UNCHECKED_CAST")
      project.extra["excludeJdk"] as? List<String> ?: emptyList()
    })
  val allowReflectiveAccessToJdk = objects.property<Boolean>().convention(
    providers.provider { project.extra["allowReflectiveAccessToJdk"] as? Boolean ?: false }
  )

  companion object {
    const val NAME = "jvmConstraint"
  }
}
