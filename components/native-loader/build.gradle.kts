plugins {
  `java-library`
  id("dd-trace-java.module.platform-component")
}

dependencies {
  implementation(project(":components:environment"))

  testImplementation("com.google.jimfs:jimfs:1.1")
}
