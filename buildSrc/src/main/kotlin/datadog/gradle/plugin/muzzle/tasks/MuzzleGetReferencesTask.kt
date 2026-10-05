package datadog.gradle.plugin.muzzle.tasks

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.build.event.BuildEventsListenerRegistry
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.net.URLClassLoader
import javax.inject.Inject

@CacheableTask
abstract class MuzzleGetReferencesTask @Inject constructor(
  objects: ObjectFactory,
) : AbstractMuzzleTask() {

  @get:Inject
  abstract val buildEventsListenerRegistry: BuildEventsListenerRegistry

  init {
    description = "Print references created by instrumentation muzzle"
    outputs.upToDateWhen { true }
  }

  @get:InputFiles
  @get:Classpath
  val classpath: ConfigurableFileCollection = objects.fileCollection()

  // This output is only used to make the task cacheable, this is not exposed
  @get:OutputFile
  val outputFile = objects.fileProperty().convention(
    project.layout.buildDirectory.file("reports/references.txt")
  )

  @TaskAction
  fun printMuzzle() {
    val cl = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), null)
    val printMethod: Method = cl.loadClass("datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin")
      .getMethod(
        "printMuzzleReferences",
        ClassLoader::class.java,
        PrintWriter::class.java,
      )
    val stringWriter = StringWriter()
    printMethod.invoke(null, cl, PrintWriter(stringWriter))

    outputFile.get().asFile.writeText(stringWriter.toString())
  }
}
