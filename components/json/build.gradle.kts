plugins {
  id("me.champeau.jmh")
  id("dd-trace-java.module.internal-platform-component")
}

jmh {
  jmhVersion = libs.versions.jmh.get()
}
