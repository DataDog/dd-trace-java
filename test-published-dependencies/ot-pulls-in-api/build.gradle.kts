plugins {
  java
  application
}

java {
  disableAutoTargetJvm()
}

dependencies {
  implementation("com.datadoghq:dd-trace-ot:$version")
  testImplementation(platform("org.junit:junit-bom:5.14.1"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
}

application {
  mainClass = "test.published.dependencies.App"
}
