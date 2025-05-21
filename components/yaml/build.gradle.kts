plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

// https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.4/snakeyaml-2.4.pom
dependencies {
  implementation("org.yaml", "snakeyaml", "2.4")
}
