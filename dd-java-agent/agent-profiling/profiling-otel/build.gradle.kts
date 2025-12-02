plugins {
  `java-library`
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

jmh {
  jmhVersion = libs.versions.jmh.get()

  // Fast benchmarks by default (essential hot-path only)
  // Run with: ./gradlew jmh
  // Override includes with: ./gradlew jmh -Pjmh.includes=".*"
  includes = listOf(".*intern(String|Function|Stack)", ".*convertStackTrace")

  // Override parameters with: -Pjmh.params="uniqueEntries=1000,hitRate=0.0"
}

// Full benchmark suite with all benchmarks and default parameters
// Estimated time: ~40 minutes
tasks.register<JavaExec>("jmhFull") {
  group = "benchmark"
  description = "Runs the full JMH benchmark suite (all benchmarks, all parameters)"
  dependsOn(tasks.named("jmhCompileGeneratedClasses"))

  classpath = sourceSets["jmh"].runtimeClasspath
  mainClass.set("org.openjdk.jmh.Main")
  args = listOf("-rf", "json")
}

repositories {
  maven {
    url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    mavenContent {
      snapshotsOnly()
    }
  }
}

dependencies {
  implementation("io.btrace", "jafar-parser", "0.0.1-SNAPSHOT")
  implementation(project(":internal-api"))
  implementation(project(":components:json"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.jmc)
  testImplementation(libs.jmc.flightrecorder.writer)
}
