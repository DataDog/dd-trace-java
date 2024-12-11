//buildscript {
//  extra.set("minJavaVersionForTests", JavaVersion.VERSION_17)
//}

plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  testImplementation(project(":dd-smoke-tests"))
}


val applicationDir = projectDir.resolve("application")
val applicationBuildDir = layout.buildDirectory.dir("application").get().asFile
val myApplicationJar = "${applicationBuildDir}/libs/smoke-tests-template.jar"

tasks.register<Exec>("buildTestApplication") {
  val launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(17)
  }.get()
  val isWindows = System.getProperty("os.name").lowercase().contains("win")
  val gradlewCommand = if (isWindows) "gradlew.bat" else "gradlew"

  workingDir(applicationDir)
  environment("GRADLE_OPTS", "-Dorg.gradle.jvmargs=-Xmx512M")
  environment("JAVA_HOME", launcher.metadata.installationPath)
  commandLine(
    "${rootDir}/${gradlewCommand}",
    "bootJar",
    "--no-daemon",
    "--max-workers=4",
    "-PappBuildDir=$applicationBuildDir",
    "-PapiJar=${project(":dd-trace-api").tasks.jar.get().archiveFile.get()}"
  )

  inputs.files(fileTree(applicationDir) {
    include("**/*")
    exclude(".gradle/**")
  }).withPropertyName("application")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  outputs.cacheIf { true }
  outputs.dir(applicationBuildDir)
    .withPropertyName("applicationBuild")
}

tasks.named("compileTestJava") {
  dependsOn("buildTestApplication")
}

tasks.withType(Test::class.java) {
  jvmArgs("-Ddatadog.smoketest.application.jar.path=${myApplicationJar}")
}
