import com.github.spotbugs.snom.SpotBugsTask

plugins {
  java
  id("com.github.spotbugs")
}

spotbugs {
  ignoreFailures = false
  // Exclude known types and packages
  excludeFilter = rootDir.resolve("gradle/spotbugFilters/exclude.xml")
  showProgress = !providers.environmentVariable("CI").isPresent
}

val productionTaskName = Regex("spotbugsMain(?:_java\\d+)?")

tasks.withType<SpotBugsTask>().configureEach {
  when {
    project.path.startsWith(":dd-smoke-tests") -> {
      enabled = false
    }
    productionTaskName.matches(name) -> {
      omitVisitors = listOf(
        "ConstructorThrow",
        "DefaultEncodingDetector",
        "DoInsideDoPrivileged",
        "DontUseEnum",
        "DroppedException",
        "FindDeadLocalStores",
        "FindHEmismatch",
        "FindNullDeref",
        "FindReturnRef",
        "FindRunInvocations",
        "FindUselessControlFlow",
        "InitializationChain",
        "LazyInit",
        "LoadOfKnownNullValue",
        "LostLoggerDueToWeakReference",
        "MethodReturnCheck",
        "MutableStaticFields",
        "Naming",
        "RuntimeExceptionCapture",
        "SerializableIdiom",
        "UnreadFields",
        )
    }
    else -> {
      // static-analysis-rules skill: enable selected test tasks here with their own detector configuration.
      enabled = false
    }
  }

  launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.current()
  }

  val reportFile = layout.buildDirectory.file("reports/spotbugs/$name.html")
  reports {
    create("html") {
      required = true
      outputLocation = reportFile
      setStylesheet("fancy-hist.xsl")
    }
  }
}

val libs = versionCatalogs.named("libs")
dependencies {
  compileOnly(libs.findLibrary("spotbugs-annotations").get())
  testImplementation(libs.findLibrary("jsr305").get())
}
