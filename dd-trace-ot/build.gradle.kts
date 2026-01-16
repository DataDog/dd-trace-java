import groovy.lang.Closure

plugins {
  `java-library`
  id("com.gradleup.shadow")
  id("me.champeau.jmh")
}

description = "dd-trace-ot"

apply(from = rootDir.resolve("gradle/java.gradle"))
apply(from = rootDir.resolve("gradle/publish.gradle"))

// TODO raise these when equals() and hashCode() are excluded
val minimumBranchCoverage by extra(0.5)
val minimumInstructionCoverage by extra(0.5)

val excludedClassesCoverage by extra(
  listOf(
    // This is mainly equals() and hashCode()
    "datadog.opentracing.OTScopeManager.OTScope",
    "datadog.opentracing.OTScopeManager.FakeScope",
    "datadog.opentracing.OTSpan",
    "datadog.opentracing.OTSpanContext",
    "datadog.opentracing.CustomScopeManagerWrapper.CustomScopeState",
    // The builder is generated
    "datadog.opentracing.DDTracer.DDTracerBuilder"
  )
)

// Helper extensions for custom methods from Groovy DSL
fun addTestSuite(name: String) {
  (project.extra["addTestSuite"] as? Closure<*>)?.call(name)
}

addTestSuite("ot31CompatibilityTest")
addTestSuite("ot33CompatibilityTest")

dependencies {
  annotationProcessor(libs.autoservice.processor)
  compileOnly(libs.autoservice.annotation)

  modules {
    module("com.squareup.okio:okio") {
      replacedBy("com.datadoghq.okio:okio") // embed our patched fork
    }
  }

  api(project(":dd-trace-api"))
  implementation(project(":dd-trace-core")) {
    // why all communication pulls in remote config is beyond me...
    exclude(group = "com.datadoghq", module = "remote-config-core")
  }
  // exception replay
  implementation(project(":dd-java-agent:agent-debugger:debugger-bootstrap"))

  // OpenTracing
  api("io.opentracing:opentracing-api:0.32.0")
  api("io.opentracing:opentracing-noop:0.32.0")
  api("io.opentracing:opentracing-util:0.32.0")
  api("io.opentracing.contrib:opentracing-tracerresolver:0.1.6")

  api(libs.slf4j)
  api(libs.jnr.unixsocket)

  implementation(project(":dd-trace-ot:correlation-id-injection"))

  testImplementation(project(":dd-java-agent:testing"))

  // Kotlin accessors not generated if not coming from plugin
  add("ot33CompatibilityTestImplementation", "io.opentracing:opentracing-api") {
    version {
      strictly("0.33.0")
    }
  }
  add("ot33CompatibilityTestImplementation", "io.opentracing:opentracing-util") {
    version {
      strictly("0.33.0")
    }
  }
  add("ot33CompatibilityTestImplementation", "io.opentracing:opentracing-noop") {
    version {
      strictly("0.33.0")
    }
  }
}

// gradle can't downgrade the opentracing dependencies with `strictly`
configurations.matching { it.name.startsWith("ot31") }.configureEach {
  resolutionStrategy {
    force("io.opentracing:opentracing-api:0.31.0")
    force("io.opentracing:opentracing-util:0.31.0")
    force("io.opentracing:opentracing-noop:0.31.0")
  }
}

tasks.test {
  finalizedBy(
    tasks.named("ot31CompatibilityTest"),
    tasks.named("ot33CompatibilityTest"),
  )
}

tasks.jar {
  destinationDirectory = layout.buildDirectory.dir("libs-unbundled")
  archiveClassifier = "unbundled"
}

// shadowJar configuration

// The shadowJar block configures the shadow JAR packaging
// and dependency relocation/exclusion rules.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
  archiveClassifier = ""

  dependencies {
    // direct dependencies
    exclude(project(":dd-trace-api"))
    exclude(dependency("io.opentracing:"))
    exclude(dependency("io.opentracing.contrib:"))
    exclude(dependency("org.slf4j:"))
    exclude(dependency("com.github.jnr:"))
    // indirect dependency of JNR, no need to embed
    exclude(dependency("org.ow2.asm:"))
  }

  relocate("com.", "ddtrot.com.") {
    // leave our PatchInit class shaded even though its not used in this deployment
    // unfortunately the shadow plugin doesn't let us completely remove this class
    exclude("%regex[com/kenai/jffi/(?!PatchInit)[^/]*]")
  }
  relocate("dogstatsd/", "ddtrot/dogstatsd/")
  relocate("okhttp3.", "ddtrot.okhttp3.")
  relocate("okio.", "ddtrot.okio.")
  relocate("org.", "ddtrot.org.") {
    exclude("org.slf4j.*")
  }
  relocate("datadog.", "ddtrot.dd.") {
    exclude("datadog.opentracing.*")
    exclude("datadog.opentracing.resolver.*")
    exclude("%regex[datadog/trace/api/(?!Functions|Endpoint)[^/]*]")
    exclude("datadog.trace.api.config.*")
    exclude("datadog.trace.api.experimental.*")
    exclude("datadog.trace.api.interceptor.*")
    exclude("datadog.trace.api.internal.*")
    exclude("datadog.trace.api.internal.util.*")
    exclude("datadog.trace.api.profiling.*")
    exclude("datadog.trace.api.sampling.*")
    exclude("datadog.trace.context.*")
  }

  duplicatesStrategy = DuplicatesStrategy.FAIL

  // Remove some cruft from the final jar.
  // These patterns should NOT include **/META-INF/maven/**/pom.properties, which is
  // used to report our own dependencies.
  exclude("**/META-INF/maven/**/pom.xml")
  exclude("META-INF/proguard/")
  exclude("/META-INF/*.kotlin_module")
}

jmh {
  //  include = [".*URLAsResourceNameBenchmark"]
  //  include = ['some regular expression'] // include pattern (regular expression) for benchmarks to be executed
  //  exclude = ['some regular expression'] // exclude pattern (regular expression) for benchmarks to be executed
  iterations = 1 // Number of measurement iterations to do.
  benchmarkMode = listOf("thrpt", "avgt", "ss")
  // Benchmark mode. Available modes are: [Throughput/thrpt, AverageTime/avgt, SampleTime/sample, SingleShotTime/ss, All/all]
  batchSize = 1
  // Batch size: number of benchmark method calls per operation. (some benchmark modes can ignore this setting)
  fork = 1 // How many times to forks a single benchmark. Use 0 to disable forking altogether
  failOnError = false // Should JMH fail immediately if any benchmark had experienced the unrecoverable error?
  forceGC = false // Should JMH force GC between iterations?
  //  jvm = 'myjvm' // Custom JVM to use when forking.
  //  jvmArgs = ['Custom JVM args to use when forking.']
  //  jvmArgsAppend = ['Custom JVM args to use when forking (append these)']
  //  jvmArgsPrepend =[ 'Custom JVM args to use when forking (prepend these)']
  //  humanOutputFile = project.file("${project.buildDir}/reports/jmh/human.txt") // human-readable output file
  //  resultsFile = project.file("${project.buildDir}/reports/jmh/results.txt") // results file
  //  operationsPerInvocation = 10 // Operations per invocation.
  //  benchmarkParameters =  [:] // Benchmark parameters.
  //  profilers = ['stack'] // Use profilers to collect additional data. Supported profilers: [cl, comp, gc, stack, perf, perfnorm, perfasm, xperf, xperfasm, hs_cl, hs_comp, hs_gc, hs_rt, hs_thr]
  timeOnIteration = "1s" // Time to spend at each measurement iteration.
  //  resultFormat = 'CSV' // Result format type (one of CSV, JSON, NONE, SCSV, TEXT)
  //  synchronizeIterations = false // Synchronize iterations?
  //  threads = 2 // Number of worker threads to run with.
  //  threadGroups = [2,3,4] //Override thread group distribution for asymmetric benchmarks.
  //  timeout = '1s' // Timeout for benchmark iteration.
  timeUnit = "us" // Output time unit. Available time units are: [m, s, ms, us, ns].
  //  verbosity = 'NORMAL' // Verbosity mode. Available modes are: [SILENT, NORMAL, EXTRA]
  warmup = "2s" // Time to spend at each warmup iteration.
  //  warmupBatchSize = 10 // Warmup batch size: number of benchmark method calls per operation.
  warmupForks = 1 // How many warmup forks to make for a single benchmark. 0 to disable warmup forks.
  warmupIterations = 1 // Number of warmup iterations to do.
  //  warmupMode = 'INDI' // Warmup mode for warming up selected benchmarks. Warmup modes are: [INDI, BULK, BULK_INDI].
  //  warmupBenchmarks = ['.*Warmup'] // Warmup benchmarks to include in the run in addition to already selected. JMH will not measure these benchmarks, but only use them for the warmup.

  //  zip64 = true // Use ZIP64 format for bigger archives
  jmhVersion = libs.versions.jmh.get()
  //  includeTests = true // Allows to include test sources into generate JMH jar, i.e. use it when benchmarks depend on the test classes.
  //  duplicateClassesStrategy = 'warn' // Strategy to apply when encountring duplicate classes during creation of the fat jar (i.e. while executing jmhJar task)
}
