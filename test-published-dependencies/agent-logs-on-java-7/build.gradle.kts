import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.application
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.java
import org.gradle.process.CommandLineArgumentProvider

plugins {
  java
  application
}

java {
  disableAutoTargetJvm()

  toolchain {
    languageVersion = JavaLanguageVersion.of(8)
  }

  sourceCompatibility = JavaVersion.VERSION_1_7
  targetCompatibility = JavaVersion.VERSION_1_7
}

val agent = configurations.register("agent")

dependencies {
  agent("com.datadoghq:dd-java-agent:$version")
  testImplementation(platform("org.junit:junit-bom:5.14.1"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

abstract class AgentLogsOnJava7JvmArguments : CommandLineArgumentProvider {
  @get:Classpath
  abstract val agentClasspath: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val appJar: RegularFileProperty

  override fun asArguments(): Iterable<String> = listOf(
    "-Dtest.published.dependencies.agent=${agentClasspath.singleFile}",
    "-Dtest.published.dependencies.jar=${appJar.get().asFile}",
  )
}

val jarTask = tasks.jar

tasks.test {
  dependsOn(jarTask)
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
  }
  jvmArgumentProviders.add(
    objects.newInstance(AgentLogsOnJava7JvmArguments::class.java).apply {
      agentClasspath.from(agent)
      appJar.set(jarTask.flatMap { it.archiveFile })
    }
  )
}

tasks.jar {
  manifest {
    attributes("Main-Class" to "test.published.dependencies.App")
  }
}

application {
  mainClass = "test.published.dependencies.App"
}

tasks.compileTestJava {
  sourceCompatibility = JavaVersion.VERSION_1_8.toString()
  targetCompatibility = JavaVersion.VERSION_1_8.toString()
}
