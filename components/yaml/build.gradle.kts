plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation("org.snakeyaml", "snakeyaml-engine", "2.9")
}
