import groovy.lang.Closure

plugins {
  `java-library`
}

muzzle {
  pass {
    group = "org.apache.httpcomponents.client5"
    module = "httpclient5"
    versions = "[5.0,)"
    assertInverse = true
  }
}

apply(from = "$rootDir/gradle/java.gradle")

// Helper extension for custom method from Groovy DSL
fun addTestSuiteForDir(name: String, dirName: String) {
  (project.extra["addTestSuiteForDir"] as? Closure<*>)?.call(name, dirName)
}

addTestSuiteForDir("latestDepTest", "test")

dependencies {
  compileOnly("org.apache.httpcomponents.client5:httpclient5:5.0")

  testImplementation("org.apache.httpcomponents.client5:httpclient5:5.0")

  // Kotlin accessors are not generated if not created by a plugin or explicitly declared
  add("latestDepTestImplementation", "org.apache.httpcomponents.client5:httpclient5:+")
}
