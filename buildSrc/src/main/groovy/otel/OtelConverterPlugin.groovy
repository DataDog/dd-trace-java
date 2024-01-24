package otel

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import otel.muzzle.MuzzleConverter

import java.nio.file.Files
import java.nio.file.Path

import static groovy.io.FileType.FILES

class OtelConverterPlugin implements Plugin<Project> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelConverterPlugin.class)

  @Override
  void apply(Project project) {
    // Declare "javaagent" configuration to declare dependencies to convert
    project.configurations {
      javaagent {
        visible = false // Only accessible inside this project
        transitive = false // Only fetch javaagent modules and not their dependencies
      }
    }
    // Create and initialize extension
    def extension = project.extensions.create('otelConverter', OtelConverterExtension)
    extension.sourceDirectory.convention(project.layout.buildDirectory.dir('classes/java/javaagent'))
    extension.targetDirectory.convention(project.layout.buildDirectory.dir('classes/java/main'))
    // Register tasks
    def fetchTask = project.tasks.register('fetchOtelJavaAgent', FetchJavaAgent).get()
    def convertTask = project.tasks.register('convertOtelJavaAgent', ConvertJavaAgent).get()
    def buildTask = project.tasks.named('build').get()
    buildTask.dependsOn(convertTask)
    convertTask.dependsOn(fetchTask)
  }
}

/** Fetch and extract OpenTelemetry javaagent artifacts */
class FetchJavaAgent extends DefaultTask {
  @TaskAction
  void fetch() {
    println '>>> Running fetch task'
    project.copy {
      project.configurations.named('javaagent').get().resolve().each {
        from(project.zipTree(it)) {
          include '**/*.class'
          // TODO Include META-INF like native-image configuration?
          // TODO Include resource files?
        }
      }
      into project.extensions.getByType(OtelConverterExtension).sourceDirectory
    }
  }
}

class ConvertJavaAgent extends DefaultTask {
  @TaskAction
  void convert() {
    println '>>> Running convert task'
    def extension = project.extensions.getByType(OtelConverterExtension)
    def sourceDirectory = extension.sourceDirectory.asFile.get()
    def targetDirectory = extension.targetDirectory.asFile.get()
    def converter = new Converter(sourceDirectory, targetDirectory)
    sourceDirectory.traverse(
      type: FILES,
      nameFilter: ~/.*\.class$/
    ) {
      converter.convert(it)
    }
  }
}

// TODO Merge with its task?
class Converter {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelConverterPlugin.class)
  private final File sourceDirectory
  private final File targetDirectory

  Converter(File sourceDirectory, File targetDirectory) {
    this.targetDirectory = targetDirectory
    this.sourceDirectory = sourceDirectory
  }

  void convert(File file) {
    // Compute target file location
    def relativePath = this.sourceDirectory.relativePath(file)
    Path targetFile = this.targetDirectory.toPath().resolve(relativePath)
    Path targetFolder = targetFile.parent
    LOGGER.debug("Converting class file ${relativePath}")
    // Ensure target folder exists
    if (!Files.isDirectory(targetFolder)) {
      Files.createDirectories(targetFolder)
    }

    try (InputStream inputStream = Files.newInputStream(file.toPath())) {
      def writer = new ClassWriter(0) // TODO flags?
      def virtualFieldConverter = new VirtualFieldConverter(writer, file.name)
      def muzzleConverter = new MuzzleConverter(virtualFieldConverter, file.name)
      def javaAgentApiChecker = new OtelApiVerifier(muzzleConverter, file.name)
      // TODO Insert more visitors here
      def reader = new ClassReader(inputStream)
      reader.accept(javaAgentApiChecker, 0) // TODO flags?
      Files.write(targetFile, writer.toByteArray())
    }
  }
}

interface OtelConverterExtension {
  /** Where to find original javaagent class files */
  DirectoryProperty getSourceDirectory()
  /** Where to store converted javaagent class files */
  DirectoryProperty getTargetDirectory()
}
