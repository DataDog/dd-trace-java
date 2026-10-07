import datadog.gradle.plugin.instrument.BuildTimeInstrumentationExtension

plugins {
  id("dd-trace-java.conventions.java")
  id("de.thetaphi.forbiddenapis")
}

// These plugins are produced by buildSrc and are only available at runtime.
// Once we can move to build-logic we can move these to plugins block and use kotlin accessors.
pluginManager.apply("dd-trace-java.build-time-instrumentation")
pluginManager.apply("dd-trace-java.muzzle")

extensions.configure<BuildTimeInstrumentationExtension> {
  plugins.addAll(
    "datadog.trace.agent.tooling.advice.AdviceScanningGradlePlugin",
    "datadog.trace.agent.tooling.bytebuddy.NewTaskForGradlePlugin",
    "datadog.trace.agent.tooling.bytebuddy.reqctx.RewriteRequestContextAdvicePlugin"
  )
}

configurations.named("muzzleBootstrap") {
  // Vendored this in the agent jar.
  exclude(group = "org.snakeyaml", module = "snakeyaml-engine")
}

tasks.withType<Javadoc>().configureEach { enabled = false }

forbiddenApis {
  signaturesFiles = files(
    "$rootDir/gradle/forbiddenApiFilters/main.txt",
    "$rootDir/gradle/forbiddenApiFilters/instrumentation.txt"
  )
}

val libs = versionCatalogs.named("libs")
val byteBuddy = libs.findLibrary("bytebuddy").get()
val additionalImplementation = Regex("main_java\\d+Implementation")

// Configure base dependencies for additional Java source sets.
configurations.matching { additionalImplementation.matches(it.name) }.configureEach {
  dependencies.add(project.dependencies.project(":dd-trace-api"))
  dependencies.add(project.dependencies.project(":dd-java-agent:agent-tooling"))
  dependencies.addLater(byteBuddy)
}

dependencies {
  add(
    "buildTimeInstrumentationPlugin",
    project(
      path = ":dd-java-agent:agent-tooling",
      configuration = "buildTimeInstrumentationToolingPlugins"
    )
  )

  val autoServiceProcessor = libs.findLibrary("autoservice-processor").get()
  val autoServiceAnnotation = libs.findLibrary("autoservice-annotation").get()

  // Main
  annotationProcessor(project(":dd-java-agent:instrumentation-annotation-processor"))
  annotationProcessor(autoServiceProcessor)
  compileOnly(autoServiceAnnotation)

  implementation(project(":dd-trace-api"))
  implementation(project(":dd-java-agent:agent-tooling"))
  implementation(byteBuddy)

  // Tests
  testAnnotationProcessor(autoServiceProcessor)
  testCompileOnly(autoServiceAnnotation)

  // Include core JDK instrumentations to check interoperability with other instrumentation.
  testImplementation(project(":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-1.8"))
  testImplementation(project(":dd-java-agent:instrumentation:java:java-lang:java-lang-classloading-1.8"))
  testImplementation(project(":dd-java-agent:instrumentation-testing"))
}

tasks.withType<Test>().configureEach {
  if (name in listOf("latestDepTest", "latestDepForkedTest", "latestDepTestForkedTest")) {
    jvmArgs("-Dtest.dd.latestDepTest=true")
  }
}
