package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

interface TestJvmConstraintsExtension {
  val minJavaVersionForTests: Property<JavaVersion>
  val maxJavaVersionForTests: Property<JavaVersion>
  val forceJdk: ListProperty<String>
  val excludeJdk: ListProperty<String>
  val allowReflectiveAccessToJdk: Property<Boolean>

  companion object {
    const val NAME = "testJvmConstraint"
  }
}
