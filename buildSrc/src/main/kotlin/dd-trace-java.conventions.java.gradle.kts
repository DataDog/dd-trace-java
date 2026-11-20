plugins {
  id("dd-trace-java.dependency-locking")
  java
}

apply(from = rootDir.resolve("gradle/java_no_deps.gradle"))

val libs = versionCatalogs.named("libs")
dependencies {
  testImplementation(platform(libs.findLibrary("junit-bom").get()))
  testImplementation(libs.findLibrary("tabletest").get())
  testImplementation(libs.findBundle("test-logging").get())

  testRuntimeOnly(libs.findBundle("junit5").get())
  testRuntimeOnly(libs.findBundle("junit-platform").get())
  testRuntimeOnly(libs.findLibrary("mokito-core").get())
}
