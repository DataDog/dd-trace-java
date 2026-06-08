import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(project(":internal-api"))
  api(libs.jctools)
}

// jctools-core-jdk11 contains Java 11 class files; JDK 8 javadoc cannot read them.
tasks.named<Javadoc>("javadoc") {
  javadocTool.set(
    javaToolchains.javadocToolFor {
      languageVersion.set(JavaLanguageVersion.of(11))
    }
  )
}
