plugins {
  id("dd-trace-java.jmh-conventions")
}

apply(from = "$rootDir/gradle/java.gradle")

jmh {
  jmhVersion = libs.versions.jmh.get()
}
