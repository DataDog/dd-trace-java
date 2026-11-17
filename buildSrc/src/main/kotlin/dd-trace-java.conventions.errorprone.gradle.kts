import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.GradleException
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
  val compilerVersion = javaCompiler.map { it.metadata.languageVersion.asInt() }
  val compileTaskPath = path

  options.errorprone {
    isEnabled = basePackage.map { true }.orElse(false)
    disableAllChecks = true
    error("NullAway")
    checkOptions.put("NullAway:AnnotatedPackages", basePackage)
    option("NullAway:TreatGeneratedAsUnannotated", true)
    excludedPaths = ".*[/\\\\]build[/\\\\]generated[/\\\\].*"
  }

  val errorProneEnabled = options.errorprone.isEnabled
  options.compilerArgumentProviders.add(
    CommandLineArgumentProvider {
      if (!errorProneEnabled.get()) return@CommandLineArgumentProvider emptyList()

      // The configured Error Prone version needs JDK 21+. FLOW lets it analyse
      // methods before javac stops on errors. javac 21 also needs the flag to
      // read type-use annotations from dependency bytecode; later JDKs do not.
      when (val version = compilerVersion.get()) {
        in 1..20 -> throw GradleException(
          "Error Prone requires JDK 21 or later for $compileTaskPath, but its compiler is JDK $version. " +
            "Use a supported compiler or explicitly disable Error Prone for this task."
        )

        21 -> listOf("--should-stop=ifError=FLOW", "-XDaddTypeAnnotationsToSymbol=true")

        else -> listOf("--should-stop=ifError=FLOW")
      }
    }
  )
}
