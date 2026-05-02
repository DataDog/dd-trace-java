plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(libs.bytebuddy)
  api(libs.bytebuddyagent)
  api(libs.forbiddenapis)

  api(project(":components:environment"))
  api(project(":utils:config-utils"))
  api(project(":utils:junit-utils"))
  api(group = "commons-fileupload", name = "commons-fileupload", version = "1.5")

  compileOnly(libs.junit.jupiter)
  compileOnly(libs.logback.core)
  compileOnly(libs.logback.classic)

  compileOnly(libs.bundles.groovy)
  compileOnly(libs.bundles.spock)
}
