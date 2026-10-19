import com.github.spotbugs.snom.SpotBugsTask

plugins {
  java
  id("com.github.spotbugs")
}

spotbugs {
  ignoreFailures = true
  excludeFilter = rootProject.layout.projectDirectory.file("gradle/spotbugFilters/exclude.xml")
}

tasks.withType<SpotBugsTask>().configureEach {
  launcher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.current()
  }

  if (project.path.startsWith(":dd-smoke-tests") ||
    !(name.endsWith("Main") || name.endsWith("Main_java11"))) {
    enabled = false
    return@configureEach
  }

  showProgress = !providers.environmentVariable("CI").isPresent
  ignoreFailures = false
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
