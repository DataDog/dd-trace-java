package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.JavaVersion
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

interface TestJvmConstraintsExtension {
  /**
   * Sets an explicit minimum bound to allowed JDK version
   */
  val minJavaVersion: Property<JavaVersion>

  /**
   * Sets an explicit maximum bound to allowed JDK version
   */
  val maxJavaVersion: Property<JavaVersion>

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
   * Indicate if the test JVM allows reflective access to JDK
   * `java.base/java.lang` and `java.base/java.util` modules by
   * openning them.
   */
  val allowReflectiveAccessToJdk: Property<Boolean>

  /**
   * Require the JDK running the test (or the daemon JVM, when no `testJvm` is selected) to
   * be `native-image` capable — i.e. a GraalVM-flavoured distribution that ships
   * `lib/svm/bin/native-image`. Tasks running on JDKs that don't satisfy this requirement
   * are skipped via `onlyIf`, matching the gating model used by [minJavaVersion] and
   * [includeJdk]. Defaults to `false` (no native-image requirement).
   */
  val nativeImageCapable: Property<Boolean>

  companion object {
    const val TEST_JVM_CONSTRAINTS = "testJvmConstraints"
  }
}
