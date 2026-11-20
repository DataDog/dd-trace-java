import datadog.buildlogic.lettuce.LettuceTestsExtension
import groovy.lang.Closure
import java.util.function.BiConsumer

/*
 * Runs the Lettuce instrumentation tests against the lettuce-core versions declared with
 * `lettuceTests { suite(name, version) }`.
 *
 * The lettuce-5.0 instrumentation module and its nested `*-test` modules apply this convention, so
 * that the suites spread across CI slots. All of them compile the tests of the instrumentation
 * module.
 */

plugins {
  java
}

// Loaded by the root project, which keeps its image resolver in one classloader
pluginManager.apply("dd-trace-java.testcontainers")

val instrumentationPath = ":dd-java-agent:instrumentation:lettuce:lettuce-5.0"
val testSources = isolated.rootProject.projectDirectory.dir("dd-java-agent/instrumentation/lettuce/lettuce-5.0/src/test")
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.create<LettuceTestsExtension>(
  "lettuceTests",
  BiConsumer<String, String> { suite, version ->
    // Registered by gradle/test-suites.gradle, which the module conventions apply
    val addTestSuiteForDir = extra["addTestSuiteForDir"] as Closure<*>
    addTestSuiteForDir.call(suite, "test")
    sourceSets.named(suite) {
      java.setSrcDirs(listOf(testSources.dir("java")))
      the<GroovySourceDirectorySet>().setSrcDirs(listOf(testSources.dir("groovy")))
      resources.setSrcDirs(listOf(testSources.dir("resources")))
    }
    dependencies.add("${suite}Implementation", "io.lettuce:lettuce-core:$version")
  },
)

dependencies {
  // The Testcontainers plugin bridges its image(reference, systemProperty) helper to scripts
  val image = dependencies.extensions.extraProperties["image"] as Closure<*>
  "testContainerImage"(requireNotNull(image.call("redis:6.2.6", "test.redis.image")))

  testImplementation("com.redis.testcontainers:testcontainers-redis:1.6.2")
  testImplementation(libs.findLibrary("testcontainers").get())
  testImplementation(project(":dd-java-agent:instrumentation:reactor-core-3.1"))
  testImplementation(project(":dd-java-agent:instrumentation:reactive-streams-1.0"))
  // Needed for tests using Lettuce 5.1.0 -> Reactor 3.2.0, 6.0.0 -> 3.3.10, and 6.1.0 -> 3.3.15.
  // Reinstalling the agent retransforms Reactor classes loaded by earlier tests,
  // including MonoMetrics/FluxMetrics and their fuseable variants.
  // Without micrometer-core, verification fails when resolving CompositeMeterRegistry as a subtype
  // of MeterRegistry, for the affected reactor versions, resulting in flaky tests.
  // Retries start in a fresh JVM and avoid retransformation of these classes;
  // initial class loading can defer this check while metrics remain unused.
  testRuntimeOnly("io.micrometer:micrometer-core:1.6.5")
}

tasks.withType<Test>().configureEach {
  // Registered by gradle/java_no_deps.gradle, which the module conventions apply
  usesService(gradle.sharedServices.registrations.getByName("testcontainersLimit").service)
}

// The test-only modules get what the instrumentation module conventions give the instrumentation module tests
pluginManager.withPlugin("dd-trace-java.module.testing-support") {
  dependencies {
    testImplementation(project(instrumentationPath))
    testImplementation(project(":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-1.8"))
    testImplementation(project(":dd-java-agent:instrumentation:java:java-lang:java-lang-classloading-1.8"))
    testImplementation(project(":dd-java-agent:instrumentation:netty:netty-concurrent-4.0"))
    testImplementation(project(":dd-java-agent:instrumentation-testing"))
  }

  // The instrumentation module formats the shared sources; Spotless rejects files outside the project directory
  tasks.named { it.startsWith("spotlessGroovy") && !it.startsWith("spotlessGroovyGradle") }.configureEach {
    enabled = false
  }

  tasks.withType<Test>().configureEach {
    if (name in listOf("latestDepTest", "latestDepForkedTest")) {
      jvmArgs("-Dtest.dd.latestDepTest=true")
    }
  }
}
