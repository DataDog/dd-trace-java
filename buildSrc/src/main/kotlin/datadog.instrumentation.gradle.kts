import gradle.kotlin.dsl.accessors._3908dec5f52cdaf4b0a666e9f9731da2.compileOnly

plugins {
  id("datadog.java")
}

// Apply custom build plugins
project.apply(plugin = "datadog.instrument")
project.apply(plugin = "datadog.muzzle")

// Configure instrumenter plugin
project.configurations {
  create("instrumentPluginClasspath") {
    isVisible = false
    isCanBeConsumed = false
    isCanBeResolved = true
  }
}

the<InstrumentExtension>().getPlugins().value(listOf(
  "datadog.trace.agent.tooling.muzzle.MuzzleGradlePlugin",
  "datadog.trace.agent.tooling.bytebuddy.NewTaskForGradlePlugin",
  "datadog.trace.agent.tooling.bytebuddy.reqctx.RewriteRequestContextAdvicePlugin"
))

// Disable Javadoc generation
tasks.withType(Javadoc::class.java) {
  enabled = false
}

dependencies {
  // Apply common dependencies for instrumentation.
  implementation(project(":dd-trace-api"))
  implementation(project(":dd-java-agent:agent-tooling"))
  implementation(getLibraryFromVersionCatalog("bytebuddy"))
//    if (jdkCompile) {
//      "$jdkCompile" project(':dd-trace-api')
//      "$jdkCompile" project(':dd-java-agent:agent-tooling')
//      "$jdkCompile" libs.bytebuddy
//    }

  annotationProcessor(getLibraryFromVersionCatalog("autoservice-processor"))
  compileOnly(getLibraryFromVersionCatalog("autoservice-annotation"))

  // Include instrumentations instrumenting core JDK classes to ensure interoperability with other instrumentation
  testImplementation(project(":dd-java-agent:instrumentation:java-concurrent"))
  testImplementation(project(":dd-java-agent:instrumentation:java-concurrent:java-completablefuture"))
  // FIXME: we should enable this, but currently this fails tests for google http client
  //testImplementation project(':dd-java-agent:instrumentation:http-url-connection')
  testImplementation(project(":dd-java-agent:instrumentation:classloading"))

  testImplementation(project(":dd-java-agent:testing"))
  testAnnotationProcessor(getLibraryFromVersionCatalog("autoservice-processor"))
  testCompileOnly(getLibraryFromVersionCatalog("autoservice-annotation"))

  "instrumentPluginClasspath"(project(path = ":dd-java-agent:agent-tooling", configuration = "instrumentPluginClasspath"))
}

// TODO Does not seem to be applied
// Works when apply in module build though
tasks.named<Test>("test") {
  useJUnit()
//  useJUnitPlatform()
//
//  maxHeapSize = "1G"
//
//  testLogging {
//    events("passed")
//  }
}
//tasks.test {
//  useJUnit()
//}

//testing {
//    suites.configureEach {
//      // SpockRunner that we use to run agent tests cannot be properly ported to JUnit 5,
//      // since the framework does not provide the hooks / extension points
//      // that can be used to shadow the tested class.
//
//      // In order to mitigate this, SpockRunner extends JUnitPlatform,
//      // which is a JUnit 4 runner that allows executing JUnit 5 tests in a JUnit 4 environment
//      // (i.e. running them as JUnit 4 tests).
//
//      // So even though Spock 2 tests run on top of JUnit 5,
//      // we execute them in "compatibility mode" so that SpockRunner could shadow the test class
//      // See https://junit.org/junit5/docs/current/user-guide/#running-tests-junit-platform-runner for more details.
//      useJUnit()
//  }
//}
