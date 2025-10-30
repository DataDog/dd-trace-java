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
  /**
   * Sets an explicit minimum bound to allowed JDK version
   */
  val minJavaVersionForTests: Property<JavaVersion>

  /**
   * Sets an explicit maximum bound to allowed JDK version
   */
  val maxJavaVersionForTests: Property<JavaVersion>

  /**
   * List of allowed JDK names (passed through the `testJvm` property).
   */
  val forceJdk: ListProperty<String>

  /**
   * List of included JDK names (passed through the `testJvm` property).
   */
  val includeJdk: ListProperty<String>

  /**
   * List of excluded JDK names (passed through the `testJvm` property).
   */
  val excludeJdk: ListProperty<String>

  /**
   * Indicate if test jvm allows reflective access to JDK modules, in particular this toggle
   * openning `java.base/java.lang` and `java.base/java.util`.
   */
  val allowReflectiveAccessToJdk: Property<Boolean>

  companion object {
    const val NAME = "testJvmConstraint"
  }
}
