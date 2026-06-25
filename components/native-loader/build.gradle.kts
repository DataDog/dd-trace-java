plugins {
  `java-library`
  id("dd-trace-java.module.internal-platform-component")
}

dependencies {
  implementation(project(":components:environment"))
}
