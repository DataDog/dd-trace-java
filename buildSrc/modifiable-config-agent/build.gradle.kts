plugins {
  java
  alias(libs.plugins.shadow)
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

apply {
  from("$rootDir/../gradle/repositories.gradle")
}

dependencies {
  implementation(libs.asm)
}

tasks {
  shadowJar {
    archiveClassifier.set("")
    relocate("org.objectweb.asm", "datadog.trace.agent.test.config.shaded.asm")
    manifest {
      attributes(
        mapOf(
          "Premain-Class" to "datadog.trace.agent.test.config.ModifiableConfigAgent",
          "Can-Retransform-Classes" to "false",
          "Can-Redefine-Classes" to "false",
        ),
      )
    }
  }

  jar {
    enabled = false
  }

  build {
    dependsOn(shadowJar)
  }
}
