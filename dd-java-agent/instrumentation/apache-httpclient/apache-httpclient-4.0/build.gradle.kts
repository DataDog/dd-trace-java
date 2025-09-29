import groovy.lang.Closure

plugins {
  `java-library`
}

muzzle {
  fail {
    group = "commons-httpclient"
    module = "commons-httpclient"
    versions = "[,4.0)"
    skipVersions += "3.1-jenkins-1"
    skipVersions += "2.0-final" // broken metadata on maven central
  }
  pass {
    group = "org.apache.httpcomponents"
    module = "httpclient"
    versions = "[4.0,5)"
    assertInverse = true
  }
  pass {
    // We want to support the dropwizard clients too.
    group = "io.dropwizard"
    module = "dropwizard-client"
    versions = "[,3)" // dropwizard-client 3+ uses httpclient5
  }
}

apply(from = "$rootDir/gradle/java.gradle")

// Helper extensions for custom methods from Groovy DSL
fun addTestSuite(name: String) {
  (project.extra["addTestSuite"] as? Closure<*>)?.call(name)
}
fun addTestSuiteForDir(name: String, dirName: String) {
  (project.extra["addTestSuiteForDir"] as? Closure<*>)?.call(name, dirName)
}
fun addTestSuiteExtendingForDir(testSuiteName: String, parentSuiteName: String, dirName: String) {
  (project.extra["addTestSuiteExtendingForDir"] as? Closure<*>)?.call(testSuiteName, parentSuiteName, dirName)
}

addTestSuiteForDir("latestDepTest", "test")
addTestSuite("iastIntegrationTest")
addTestSuiteExtendingForDir("v41IastIntegrationTest", "iastIntegrationTest", "iastIntegrationTest")
addTestSuiteExtendingForDir("v42IastIntegrationTest", "iastIntegrationTest", "iastIntegrationTest")
addTestSuiteExtendingForDir("v43IastIntegrationTest", "iastIntegrationTest", "iastIntegrationTest")
addTestSuiteExtendingForDir("v44IastIntegrationTest", "iastIntegrationTest", "iastIntegrationTest")
addTestSuiteExtendingForDir("v45IastIntegrationTest", "iastIntegrationTest", "iastIntegrationTest")

dependencies {
  compileOnly("org.apache.httpcomponents:httpclient:4.0")
  testImplementation(project(":dd-java-agent:agent-iast:iast-test-fixtures"))
  testImplementation("org.apache.httpcomponents:httpclient:4.0")
  testImplementation(project(":dd-java-agent:instrumentation:apache-httpclient:apache-httpasyncclient-4.0"))
  // to instrument the integration test
  "iastIntegrationTestImplementation"(project(":dd-java-agent:agent-iast:iast-test-fixtures"))
  "iastIntegrationTestImplementation"("org.apache.httpcomponents:httpclient:4.0")
  "iastIntegrationTestRuntimeOnly"(project(":dd-java-agent:instrumentation:jetty:jetty-server:jetty-server-9.0"))
  "iastIntegrationTestRuntimeOnly"(project(":dd-java-agent:instrumentation:apache-httpcore:apache-httpcore-4.0"))
  "iastIntegrationTestRuntimeOnly"(project(":dd-java-agent:instrumentation:servlet"))
  "iastIntegrationTestRuntimeOnly"(project(":dd-java-agent:instrumentation:java-lang"))
  "iastIntegrationTestRuntimeOnly"(project(":dd-java-agent:instrumentation:java-net"))
  "iastIntegrationTestRuntimeOnly"(project(":dd-java-agent:instrumentation:iast-instrumenter"))

  "v41IastIntegrationTestImplementation"("org.apache.httpcomponents:httpclient:4.1")
  "v42IastIntegrationTestImplementation"("org.apache.httpcomponents:httpclient:4.2")
  "v43IastIntegrationTestImplementation"("org.apache.httpcomponents:httpclient:4.3")
  "v44IastIntegrationTestImplementation"("org.apache.httpcomponents:httpclient:4.4")
  "v45IastIntegrationTestImplementation"("org.apache.httpcomponents:httpclient:4.5")

  // Kotlin accessors are not generated if not created by a plugin or explicitly declared
  add("latestDepTestImplementation", "org.apache.httpcomponents:httpclient:+")
}
