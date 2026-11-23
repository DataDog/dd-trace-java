pluginManager.withPlugin("dd-trace-java.module.instrumentation") {
  dependencies {
    // java-concurrent captures Netty tasks; netty-concurrent owns their activation and cleanup.
    add("testImplementation", project(":dd-java-agent:instrumentation:netty:netty-concurrent-4.0"))
  }
}
