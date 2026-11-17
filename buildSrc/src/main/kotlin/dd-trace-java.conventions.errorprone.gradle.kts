import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.process.CommandLineArgumentProvider

plugins {
  java
  id("net.ltgt.errorprone")
}

interface NullAwayExtension {
  /** Package prefix to check; an unset or blank value keeps NullAway disabled. */
  val basePackage: Property<String>
}

val libs = versionCatalogs.named("libs")
dependencies {
  errorprone(libs.findLibrary("errorprone").get())
  errorprone(libs.findLibrary("nullaway").get())
}

val nullAway = extensions.create<NullAwayExtension>("nullAway")
val mainCompileTaskName = Regex("compile(?:Main_java\\d+)?Java")

tasks.withType<JavaCompile>().configureEach {
  if (!mainCompileTaskName.matches(name)) {
    options.errorprone.isEnabled = false
    return@configureEach
  }

  val basePackage = nullAway.basePackage.filter { it.isNotBlank() }
  // Some production source sets need older compilers to access JDK internals.
  val compilerVersion = javaCompiler.map { it.metadata.languageVersion.asInt() }
  val compileTaskPath = path

  options.errorprone {
    isEnabled = providers.zip(basePackage, compilerVersion) { _, version -> version >= 21 }.orElse(false)
    check("NullAway", CheckSeverity.ERROR)
    option("NullAway:AnnotatedPackages", basePackage)
    disableAllWarnings = true // only errors for now
    option("NullAway:TreatGeneratedAsUnannotated", true)
    excludedPaths = ".*[/\\\\]build[/\\\\]generated[/\\\\].*"
  }

  options.compilerArgumentProviders.add(
    CommandLineArgumentProvider {
      if (!basePackage.isPresent) return@CommandLineArgumentProvider emptyList()

      // The configured Error Prone version needs JDK 21+. FLOW lets it analyse
      // methods before javac stops on errors. javac 21 also needs the flag to
      // read type-use annotations from dependency bytecode; later JDKs do not.
      when (val version = compilerVersion.get()) {
        in 1..20 -> {
          Logging.getLogger("dd-trace-java.conventions.errorprone").warn(
            "Error Prone is disabled for {}: compiler is JDK {}, but JDK 21 or later is required.",
            compileTaskPath,
            version
          )
          emptyList()
        }

        21 -> listOf("--should-stop=ifError=FLOW", "-XDaddTypeAnnotationsToSymbol=true")

        else -> listOf("--should-stop=ifError=FLOW")
      }
    }
  )
}
