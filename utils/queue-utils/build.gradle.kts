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
  // shortcut: keep 4.10.4 until SpotBugs fixes convergence in inherited JCTools fill methods.
  toolVersion = libs.versions.spotbugs.queue.utils
}

// jctools-core-jdk11 contains Java 11 class files; JDK 8 javadoc cannot read them.
tasks.named<Javadoc>("javadoc") {
  javadocTool.set(
    javaToolchains.javadocToolFor {
      languageVersion.set(JavaLanguageVersion.of(11))
    }
  )
}
