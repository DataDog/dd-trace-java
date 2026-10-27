import net.ltgt.gradle.errorprone.errorprone

plugins {
  `java-library`
  id("dd-trace-java.conventions.nullaway")
}

val jspecify = versionCatalogs.named("libs").findLibrary("jspecify").get()
sourceSets.matching { name != SourceSet.MAIN_SOURCE_SET_NAME && name != SourceSet.TEST_SOURCE_SET_NAME }.configureEach {
  dependencies.add(compileOnlyConfigurationName, jspecify)
}
dependencies {
  api(jspecify)
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
