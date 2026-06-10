plugins {
  `java-library`
  id("dd-trace-java.module.internal-component")
}

dependencies {
  implementation(project(":components:environment"))
}
