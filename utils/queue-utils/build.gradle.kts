import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

dependencies {
  api(project(":internal-api"))
  api(libs.jctools)
}

spotbugs {
  // Keep analyzing this module with 4.10.4 until the JCTools loop analysis fix
  // (spotbugs/spotbugs#4419) is released.
  toolVersion = "4.10.4"
}

// jctools-core-jdk11 contains Java 11 class files; JDK 8 javadoc cannot read them.
tasks.named<Javadoc>("javadoc") {
  javadocTool.set(
    javaToolchains.javadocToolFor {
      languageVersion.set(JavaLanguageVersion.of(11))
    }
  )
}
