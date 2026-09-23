import groovy.lang.Closure

plugins {
  id("dd-trace-java.module.instrumentation")
}

muzzle {
  pass {
    group = "com.microsoft.azure.functions"
    module = "azure-functions-java-spi"
    versions = "[1.0.0,)"
    extraDependency("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
  }
}

fun addTestSuiteForDir(name: String, directory: String) {
  (project.extra["addTestSuiteForDir"] as Closure<*>).call(name, directory)
}

addTestSuiteForDir("latestDepTest", "test")

dependencies {
  compileOnly("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
  compileOnly("com.microsoft.azure.functions:azure-functions-java-spi:1.0.0")

  testImplementation("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
  testImplementation("com.microsoft.azure.functions:azure-functions-java-spi:1.0.0")
  testImplementation(libs.bundles.mockito)

  add(
    "latestDepTestImplementation",
    "com.microsoft.azure.functions:azure-functions-java-core-library:+"
  )
  add(
    "latestDepTestImplementation",
    "com.microsoft.azure.functions:azure-functions-java-spi:+"
  )
}
