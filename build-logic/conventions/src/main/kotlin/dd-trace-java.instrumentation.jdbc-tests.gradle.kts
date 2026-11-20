import datadog.buildlogic.jdbc.JdbcTestsExtension
import groovy.lang.Closure
import java.util.function.BiConsumer
import java.util.function.Consumer

/*
 * Runs the JDBC instrumentation tests in the test suites declared with `jdbcTests { suite(name) }`
 * or `jdbcTests { latestDepSuite(name) }`.
 *
 * The jdbc instrumentation module and its nested `*-test` modules apply this convention, so that
 * the suites spread across CI slots and run concurrently. All of them compile the tests of the
 * instrumentation module.
 */

plugins {
  java
}

// Loaded by the root project, which keeps its image resolver in one classloader
pluginManager.apply("dd-trace-java.testcontainers")

val instrumentationPath = ":dd-java-agent:instrumentation:jdbc"
val testSources = isolated.rootProject.projectDirectory.dir("dd-java-agent/instrumentation/jdbc/src/test")
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val testcontainersVersion = libs.findVersion("testcontainers").get().requiredVersion

extensions.create<JdbcTestsExtension>(
  "jdbcTests",
  BiConsumer<String, Boolean> { suite, withForkedTest ->
    // Registered by gradle/test-suites.gradle, which the module conventions apply
    val addTestSuiteForDir = extra["addTestSuiteForDir"] as Closure<*>
    addTestSuiteForDir.call(suite, "test", withForkedTest)
    sourceSets.named(suite) {
      java.setSrcDirs(listOf(testSources.dir("java")))
      the<GroovySourceDirectorySet>().setSrcDirs(listOf(testSources.dir("groovy")))
      resources.setSrcDirs(listOf(testSources.dir("resources")))
    }
  },
  Consumer<String> { suite ->
    val implementation = "${suite}Implementation"
    dependencies {
      implementation("com.h2database:h2:2.2.+") // 2.3+ requires Java 11
      implementation("org.apache.derby:derby:10.14.+")
      implementation("org.hsqldb:hsqldb:2.5+")

      implementation("org.apache.tomcat:tomcat-jdbc:10.0.+")
      implementation("org.apache.tomcat:tomcat-juli:10.0.+")
      implementation("com.zaxxer:HikariCP:4.+") // 5+ requires Java 11
      // Broke from 0.10.+: https://datadoghq.atlassian.net/browse/AIDM-165
      implementation("com.mchange:c3p0:0.9.+")
    }
  },
)

// This non-Docker-Hub image has a separate mirror in CI.
val sqlServerImage =
  if (providers.environmentVariable("CI").isPresent) {
    "registry.ddbuild.io/images/mirror/sqlserver:latest"
  } else {
    "mcr.microsoft.com/mssql/server:latest"
  }

dependencies {
  // The Testcontainers plugin bridges its image(reference, systemProperty) helper to scripts
  val image = dependencies.extensions.extraProperties["image"] as Closure<*>
  "testContainerImage"(requireNotNull(image.call("postgres:11.2", "test.postgres.image")))
  "testContainerImage"(requireNotNull(image.call("mysql:8.0", "test.mysql.image")))
  "testContainerImage"(requireNotNull(image.call(sqlServerImage, "test.sqlserver.image")))
  "testContainerImage"(requireNotNull(image.call("gvenzl/oracle-free:23.5-slim-faststart", "test.oracle.image")))

  testImplementation(project(":dd-java-agent:agent-iast:iast-test-fixtures"))

  // jdbc unit testing
  testImplementation("com.h2database:h2:[1.3.168,1.3.169]") // first jdk 1.6 compatible
  testImplementation("org.apache.derby:derby:10.6.1.0")
  testImplementation("org.hsqldb:hsqldb:2.0.0")
  testImplementation("org.apache.commons:commons-dbcp2:2.10.0")

  testImplementation("org.apache.tomcat:tomcat-jdbc:7.0.19")
  // tomcat needs this to run
  testImplementation("org.apache.tomcat:tomcat-juli:7.0.19")
  testImplementation("com.zaxxer:HikariCP:2.4.0")
  testImplementation("com.mchange:c3p0:0.9.5")

  testImplementation("mysql:mysql-connector-java:8.0.23")
  testImplementation("org.postgresql:postgresql:[9.4,42.2.18]")
  testImplementation("com.microsoft.sqlserver:mssql-jdbc:10.2.0.jre8")
  testImplementation("com.oracle.database.jdbc:ojdbc8:19.19.0.0")

  testImplementation("org.testcontainers:mysql:$testcontainersVersion")
  testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
  testImplementation("org.testcontainers:mssqlserver:$testcontainersVersion")
  testImplementation("org.testcontainers:oracle-xe:$testcontainersVersion")

  testRuntimeOnly(project(":dd-java-agent:instrumentation:datadog:asm:iast-instrumenter"))
}

tasks.withType<GroovyCompile>().configureEach {
  // Registered by gradle/java_no_deps.gradle, which the module conventions apply
  val configureCompiler = project.extra["configureCompiler"] as Closure<*>
  configureCompiler.call(
    this,
    8,
    JavaVersion.VERSION_1_8,
    "Connection has new methods with new types introduced in Java 9 that are not available on Java 8.",
  )
}

tasks.withType<Test>().configureEach {
  // Registered by gradle/java_no_deps.gradle, which the module conventions apply
  usesService(gradle.sharedServices.registrations.getByName("testcontainersLimit").service)
}

// The test-only modules get what the instrumentation module conventions give the instrumentation module tests
pluginManager.withPlugin("dd-trace-java.module.testing-support") {
  dependencies {
    testImplementation(project(instrumentationPath))
    testImplementation(project(":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-1.8"))
    testImplementation(project(":dd-java-agent:instrumentation:java:java-lang:java-lang-classloading-1.8"))
    testImplementation(project(":dd-java-agent:instrumentation-testing"))
  }

  // The instrumentation module formats the shared sources; Spotless rejects files outside the project directory
  tasks.named { it.startsWith("spotlessGroovy") && !it.startsWith("spotlessGroovyGradle") }.configureEach {
    enabled = false
  }
}
