plugins {
  `java-library`
  id("dd-trace-java.module.platform-component")
}

dependencies {
  implementation(project(":components:environment"))
}
