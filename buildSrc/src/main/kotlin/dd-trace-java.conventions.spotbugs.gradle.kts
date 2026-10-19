import com.github.spotbugs.snom.SpotBugsTask

plugins {
  java
  id("com.github.spotbugs")
}

spotbugs {
  ignoreFailures = false
  excludeFilter = rootProject.layout.projectDirectory.file("gradle/spotbugFilters/exclude.xml")
  showProgress = !providers.environmentVariable("CI").isPresent
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

tasks.withType<SpotBugsTask>().configureEach {
  if (project.path.startsWith(":dd-smoke-tests") ||
    !(name.endsWith("Main") || name.endsWith("Main_java11"))) {
    enabled = false
    return@configureEach
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
