import net.ltgt.gradle.errorprone.errorprone
import org.gradle.process.CommandLineArgumentProvider

plugins {
  java
  id("net.ltgt.errorprone")
}

val libs = versionCatalogs.named("libs")
dependencies {
  errorprone(libs.findLibrary("errorprone").get())
  errorprone(libs.findLibrary("nullaway").get())
}

val productionTaskName = Regex("compile(?:Main_java\\d+)?Java")

tasks.withType<JavaCompile>().configureEach {
  options.errorprone {
    isEnabled.set(false)
    disableAllChecks.set(true)
    error("NullAway")
    option("NullAway:AnnotatedPackages", "datadog,com.datadog")
    option("NullAway:TreatGeneratedAsUnannotated", true)
    excludedPaths.set(".*[/\\\\]build[/\\\\]generated[/\\\\].*")
  }

  if (productionTaskName.matches(name)) {
    // Some production source sets need older compilers to access JDK internals.
    val compilerVersion = javaCompiler.map { it.metadata.languageVersion.asInt() }
    options.errorprone.isEnabled.set(compilerVersion.map { it >= 21 })
    options.compilerArgumentProviders.add(CommandLineArgumentProvider {
      when (compilerVersion.get()) {
        in 1..20 -> emptyList()
        21 -> listOf("--should-stop=ifError=FLOW", "-XDaddTypeAnnotationsToSymbol=true")
        else -> listOf("--should-stop=ifError=FLOW")
      }
    })
  }
}
