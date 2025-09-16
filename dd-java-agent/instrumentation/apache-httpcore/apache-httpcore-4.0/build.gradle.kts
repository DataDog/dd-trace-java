import groovy.lang.Closure

plugins {
  `java-library`
}

muzzle {
  pass {
    group = "org.apache.httpcomponents"
    module = "httpcore"
    versions = "[4.0,5)"
    assertInverse = true
  }
}

apply(from = "$rootDir/gradle/java.gradle")

// Helper extensions for custom methods from Groovy DSL
fun addTestSuiteForDir(name: String, dirName: String) {
  (project.extra["addTestSuiteForDir"] as? Closure<*>)?.call(name, dirName)
}

addTestSuiteForDir("latestDepTest", "test")

dependencies {
  compileOnly("org.apache.httpcomponents:httpcore:4.0")

  testImplementation("org.apache.httpcomponents:httpcore:4.0")

  // Kotlin accessors are not generated if not created by a plugin or explicitly declared
  add("latestDepTestImplementation", "org.apache.httpcomponents:httpcore:+")
}
