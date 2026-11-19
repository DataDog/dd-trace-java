/*
 * Adds Groovy/Spock test support and CodeNarc. Apply alongside `dd-trace-java.conventions.java`,
 * which provides the GroovyCompile configuration for projects with the `groovy` plugin.
 */
plugins {
  groovy
}

apply(from = rootDir.resolve("gradle/codenarc.gradle"))

val libs = versionCatalogs.named("libs")
dependencies {
  testImplementation(libs.findBundle("spock").get())
  testImplementation(libs.findBundle("groovy").get())
}
