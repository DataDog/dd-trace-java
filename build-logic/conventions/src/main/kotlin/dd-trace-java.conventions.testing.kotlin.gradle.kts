import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.base.TestingExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Make compiled Kotlin classes visible to Groovy/Spock tests.
fun wireKotlinOutputToGroovy(sourceSet: SourceSet) {
  val compileKotlin = tasks.named(sourceSet.getCompileTaskName("kotlin"))
  tasks.named<GroovyCompile>(sourceSet.getCompileTaskName("groovy")) {
    // Task-backed outputs avoid a Kotlin Gradle plugin API dependency and retain task ordering.
    classpath += files(compileKotlin)
  }
}

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
  val sourceSets = extensions.getByType<SourceSetContainer>()

  // Having Groovy, Kotlin and Java in the same project is a bit problematic.
  // Remove Kotlin from main to avoid compilation issues.
  sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
    val kotlin = extensions.getByName("kotlin") as SourceDirectorySet
    kotlin.setSrcDirs(emptyList<Any>())
    java.setSrcDirs(listOf("src/main/java"))
  }

  // Create Kotlin output directories to make JavaCompile tasks work.
  val createKotlinDirs = tasks.register("createKotlinDirs") {
    val dirsToCreate = listOf(layout.buildDirectory.dir("classes/kotlin/main"))
    doFirst {
      dirsToCreate.forEach { it.get().asFile.mkdirs() }
    }
    outputs.dirs(dirsToCreate)
  }

  tasks.withType<JavaCompile>().configureEach {
    inputs.files(createKotlinDirs)
  }

  // Prevent Kotlin libraries from being included in the tracer JAR.
  dependencies.add("compileOnly", libs.findLibrary("kotlin").get())

  pluginManager.withPlugin("groovy") {
    pluginManager.withPlugin("jvm-test-suite") {
      extensions.getByType<TestingExtension>().suites.withType<JvmTestSuite>().configureEach {
        wireKotlinOutputToGroovy(sources)
      }
    }

    pluginManager.withPlugin("java-test-fixtures") {
      sourceSets.named("testFixtures") {
        wireKotlinOutputToGroovy(this)
      }
    }
  }
}
