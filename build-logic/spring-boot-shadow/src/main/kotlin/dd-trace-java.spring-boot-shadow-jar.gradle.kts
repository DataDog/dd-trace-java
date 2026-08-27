import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer

plugins {
  id("com.gradleup.shadow")
}

tasks.withType<ShadowJar>().configureEach {
  if (name == "shadowJar") {
    // `configurations` is left at its convention, which the Shadow plugin already sets to
    // `runtimeClasspath`; adding it again is a no-op because the property is a `SetProperty`.

    // Spring discovery metadata can occur in multiple dependency jars. With enhanced graph
    // ordering, keeping only the first duplicate may omit required registrations.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/spring.handlers")
    append("META-INF/spring.schemas")
    append("META-INF/spring.tooling")
    transform(PropertiesFileTransformer::class.java) {
      paths.set(listOf("META-INF/spring.factories"))
      mergeStrategy.set(PropertiesFileTransformer.MergeStrategy.Append)
    }
    filesNotMatching(
      listOf(
        "META-INF/services/**",
        "META-INF/spring.handlers",
        "META-INF/spring.schemas",
        "META-INF/spring.tooling",
        "META-INF/spring.factories",
      )
    ) {
      duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
  }
}
