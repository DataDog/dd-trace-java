plugins {
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

jmh {
  jmhVersion = libs.versions.jmh.get()
}
