plugins {
  java
  application
}

java {
  disableAutoTargetJvm()

  toolchain {
    languageVersion = JavaLanguageVersion.of(8)
  }

  sourceCompatibility = JavaVersion.VERSION_1_7
  targetCompatibility = JavaVersion.VERSION_1_7
}

val agent: Configuration by configurations.creating

dependencies {
  agent("com.datadoghq:dd-java-agent:$version")
  testImplementation(platform("org.junit:junit-bom:5.9.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.named<Test>("test") {
  dependsOn("jar")
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
  jvmArgs("-Dtest.published.dependencies.agent=${agent.singleFile}")
  jvmArgs("-Dtest.published.dependencies.jar=${tasks.jar.get().archiveFile.get()}")
}

tasks.named<Jar>("jar") {
  manifest {
    attributes("Main-Class" to "test.published.dependencies.App")
  }
}

application {
  mainClass = "test.published.dependencies.App"
}

tasks.named<JavaCompile>("compileTestJava") {
  sourceCompatibility = JavaVersion.VERSION_1_8.toString()
  targetCompatibility = JavaVersion.VERSION_1_8.toString()
}
