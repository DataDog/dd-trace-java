plugins {
  `java-library`
  id("com.gradleup.shadow")
}

sourceSets {
  create("generator") {
    java.srcDir("src/generator/java")
  }
  val main by getting {
    java {
      srcDir("build/generated/sources/supported")
    }
  }
}

dependencies {
  "generatorImplementation"("com.fasterxml.jackson.core:jackson-databind:2.15.2")
  "generatorImplementation"("org.slf4j:slf4j-api:1.7.36")
  implementation(project(":dd-trace-api"))
}

apply(from = "$rootDir/gradle/java.gradle")

val compileGeneratorJava = tasks.named("compileGeneratorJava")

val generateSupportedConfigurations by tasks.registering(JavaExec::class) {
  // We can run the generator with the main sourceSet runtimeClasspath
  dependsOn(compileGeneratorJava)
  mainClass.set("datadog.generator.ParseSupportedConfigurations")
  classpath = sourceSets["generator"].runtimeClasspath

  val outputFile = layout.buildDirectory.file("generated/sources/supported/GeneratedSupportedConfigurations.java")
  args("supported-configurations.json", outputFile.get().asFile.absolutePath)

  doFirst {
    outputFile.get().asFile.parentFile.mkdirs()
  }
}
// Ensure Java compilation depends on the generated sources
sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/sources/supported"))

tasks.named("compileJava") {
  dependsOn(generateSupportedConfigurations)
}
