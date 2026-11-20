import groovy.lang.Closure

val lettuceTestSuites: Map<String, String> by extra
val testSources = rootProject.layout.projectDirectory.dir("dd-java-agent/instrumentation/lettuce/lettuce-5.0/src/test")
val addTestSuiteForDir = extra["addTestSuiteForDir"] as Closure<*>
val image = (dependencies as ExtensionAware).extra["image"] as Closure<*>
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

lettuceTestSuites.forEach { (suite, version) ->
  addTestSuiteForDir.call(suite, "test")
  extensions.getByType<SourceSetContainer>().named(suite) {
    java.setSrcDirs(listOf(testSources.dir("java")))
    extensions.getByType<GroovySourceDirectorySet>().setSrcDirs(listOf(testSources.dir("groovy")))
    resources.setSrcDirs(listOf(testSources.dir("resources")))
  }
  dependencies.add("${suite}Implementation", "io.lettuce:lettuce-core:$version")
}

dependencies {
  add("testContainerImage", requireNotNull(image.call("redis:6.2.6", "test.redis.image")))
  add("testImplementation", "com.redis.testcontainers:testcontainers-redis:1.6.2")
  add("testImplementation", libs.findLibrary("testcontainers").get())
  add("testImplementation", project(":dd-java-agent:instrumentation:reactor-core-3.1"))
  add("testImplementation", project(":dd-java-agent:instrumentation:reactive-streams-1.0"))
  // Older Reactor versions need Micrometer on the runtime classpath for the JVM
  // to verify Reactor metrics classes when the agent retransforms them.
  add("testRuntimeOnly", "io.micrometer:micrometer-core:1.6.5")
}

pluginManager.withPlugin("dd-trace-java.module.testing-support") {
  dependencies {
    add("testImplementation", project(":dd-java-agent:instrumentation:lettuce:lettuce-5.0"))
    add("testImplementation", project(":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-1.8"))
    add("testImplementation", project(":dd-java-agent:instrumentation:java:java-lang:java-lang-classloading-1.8"))
    add("testImplementation", project(":dd-java-agent:instrumentation:netty:netty-concurrent-4.0"))
    add("testImplementation", project(":dd-java-agent:instrumentation-testing"))
  }
}

val testcontainersLimit = gradle.sharedServices.registrations.named("testcontainersLimit").get().service
tasks.withType<Test>().configureEach {
  usesService(testcontainersLimit)
  if (name.endsWith("latestDepTest", ignoreCase = true) || name.endsWith("latestDepForkedTest", ignoreCase = true)) {
    jvmArgs("-Dtest.dd.latestDepTest=true")
  }
}
