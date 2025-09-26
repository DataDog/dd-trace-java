plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(libs.groovy)
  api(libs.bundles.spock)

  api(libs.bytebuddy)
  api(libs.bytebuddyagent)

  api(project(":components:environment"))
  api(group = "commons-fileupload", name = "commons-fileupload", version = "1.5")

  compileOnly(libs.logback.core)
  compileOnly(libs.logback.classic)
}
