/**
 * Adds Groovy/Spock test support. Apply alongside `dd-trace-java.conventions.java`, which provides
 * the CodeNarc and GroovyCompile configuration for projects with the `groovy` plugin.
 */
plugins {
  groovy
}

val libs = versionCatalogs.named("libs")
dependencies {
  testImplementation(libs.findBundle("spock").get())
  testImplementation(libs.findBundle("groovy").get())
}
