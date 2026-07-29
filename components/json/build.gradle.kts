plugins {
  id("dd-trace-java.module.internal-platform-component")
  id("dd-trace-java.jmh-conventions")
}

jmh {
  jmhVersion = libs.versions.jmh.get()
}
