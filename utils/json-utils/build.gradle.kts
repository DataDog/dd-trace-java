plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(libs.slf4j)
  implementation(libs.moshi)
  implementation(libs.okio)

  modules {
    module("com.squareup.okio:okio") {
      replacedBy("com.datadoghq.okio:okio") // embed our patched fork
    }
  }
}
