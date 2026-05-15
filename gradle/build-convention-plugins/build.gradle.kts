plugins {
  `kotlin-dsl`
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

repositories {
  mavenLocal()

  providers.gradleProperty("gradlePluginProxy").orNull?.let {
    maven {
      url = uri(it)
      isAllowInsecureProtocol = true
    }
  }

  providers.gradleProperty("mavenRepositoryProxy").orNull?.let {
    maven {
      url = uri(it)
      isAllowInsecureProtocol = true
    }
  }

  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.diffplug.spotless:spotless-plugin-gradle:8.4.0")
}
