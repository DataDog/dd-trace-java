package otel

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path

import static groovy.io.FileType.FILES

class OtelConverterPlugin implements Plugin<Project> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelConverterPlugin.class)

  @Override
  void apply(Project project) {
    // Create and initialize extension
    def extension = project.extensions.create('otelConverter', OtelConverterExtension)
    extension.sourceDirectory.convention(project.layout.buildDirectory.dir('javaagent'))
    extension.targetDirectory.convention(project.layout.buildDirectory.dir('classes'))
    // Create task
    project.task('convertOtelJavaAgent') {
      doLast {
        println '>>> Hello from plugin'
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
  }
}

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
      // TODO Insert more visitors here
      def reader = new ClassReader(inputStream)
      reader.accept(virtualFieldConverter, 0) // TODO flags?
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
