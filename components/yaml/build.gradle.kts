plugins {
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

jmh {
  version = "1.28"
}

// https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.4/snakeyaml-2.4.pom
dependencies {
  implementation("org.yaml", "snakeyaml", "2.4")
  implementation(project(":components:cli"))
  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":internal-api"))
}
