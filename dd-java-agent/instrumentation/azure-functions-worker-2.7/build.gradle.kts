import groovy.lang.Closure

plugins {
  id("dd-trace-java.module.instrumentation")
}

muzzle {
  // FunctionExecutionMiddleware lives in the unpublished Azure Functions Java worker.
  // The SPI pass validates its public middleware contract, but cannot check the worker's
  // internal class name or invoke signature. The test stub exercises the advice behavior;
  // worker compatibility also requires validation against a running Function App.
  pass {
    group = "com.microsoft.azure.functions"
    module = "azure-functions-java-spi"
    versions = "[1.0.0,)"
    extraDependency("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
    extraDependency("com.microsoft:durabletask-azure-functions:1.0.1")
    extraDependency("com.microsoft:durabletask-client:1.0.0")
  }
  pass {
    group = "com.microsoft"
    module = "durabletask-client"
    versions = "[1.0.0,)"
    extraDependency("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
    extraDependency("com.microsoft.azure.functions:azure-functions-java-spi:1.0.0")
    extraDependency("com.microsoft:durabletask-azure-functions:1.0.1")
  }
  pass {
    group = "com.microsoft"
    module = "durabletask-azure-functions"
    versions = "[1.0.1,)"
    extraDependency("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
    extraDependency("com.microsoft.azure.functions:azure-functions-java-spi:1.0.0")
    extraDependency("com.microsoft:durabletask-client:1.0.0")
  }
}

fun addTestSuiteForDir(name: String, directory: String) {
  (project.extra["addTestSuiteForDir"] as Closure<*>).call(name, directory)
}

addTestSuiteForDir("latestDepTest", "test")
(project.extra["addForkedTestTask"] as Closure<*>).call("latestDepTest")

dependencies {
  compileOnly("com.google.protobuf:protobuf-java:3.19.2")
  compileOnly("com.microsoft:durabletask-client:1.0.0")
  compileOnly("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
  compileOnly("com.microsoft.azure.functions:azure-functions-java-spi:1.0.0")

  testImplementation("com.microsoft:durabletask-azure-functions:1.0.1")
  testImplementation("com.microsoft:durabletask-client:1.0.0")
  testImplementation("com.google.protobuf:protobuf-java:3.19.2")
  testImplementation("com.microsoft.azure.functions:azure-functions-java-core-library:1.2.0")
  testImplementation("com.microsoft.azure.functions:azure-functions-java-spi:1.0.0")
  testImplementation(libs.bundles.mockito)

  add("latestDepTestImplementation", "com.microsoft:durabletask-azure-functions:+")
  add("latestDepTestImplementation", "com.microsoft:durabletask-client:+")
  add("latestDepTestImplementation", "com.google.protobuf:protobuf-java:+")
  add(
    "latestDepTestImplementation",
    "com.microsoft.azure.functions:azure-functions-java-core-library:+"
  )
  add(
    "latestDepTestImplementation",
    "com.microsoft.azure.functions:azure-functions-java-spi:+"
  )
}
