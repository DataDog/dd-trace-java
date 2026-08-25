import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

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
    sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME) {
      val compileKotlin = tasks.named(getCompileTaskName("kotlin"))
      tasks.named<GroovyCompile>(getCompileTaskName("groovy")) {
        classpath += files(compileKotlin)
      }
    }
  }
}
