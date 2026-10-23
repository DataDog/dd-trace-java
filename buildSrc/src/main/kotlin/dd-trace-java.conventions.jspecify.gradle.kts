import net.ltgt.gradle.errorprone.errorprone

plugins {
  id("dd-trace-java.conventions.nullaway")
}

val jspecify = versionCatalogs.named("libs").findLibrary("jspecify").get()
sourceSets.configureEach {
  dependencies.add(compileOnlyConfigurationName, jspecify)
}
dependencies {
  // Java 8 reflection needs the type annotation classes on the test runtime classpath.
  testRuntimeOnly(jspecify)
}

tasks.withType<JavaCompile>().configureEach {
  options.errorprone.checkOptions.set(
    mapOf(
      "NullAway:OnlyNullMarked" to "true",
      "NullAway:JSpecifyMode" to "true",
      "NullAway:TreatGeneratedAsUnannotated" to "true"
    )
  )
}
