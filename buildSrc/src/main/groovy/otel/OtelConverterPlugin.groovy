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
import otel.muzzle.MuzzleGenerator

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
    extension.workingDirectory.convention(project.layout.buildDirectory.dir('javaagent'))
    extension.targetDirectory.convention(project.layout.buildDirectory.dir('classes/java/main'))
    // Register task
    def convertTask = project.tasks.register('convertOtelJavaAgent', ConvertJavaAgent).get()
    def buildTask = project.tasks.named('build').get()
    buildTask.dependsOn(convertTask)
  }
}

class ConvertJavaAgent extends DefaultTask {
  @TaskAction
  void convert() {
    println '>>> Running convert task'
    // Retrieve plugin configuration
    def extension = project.extensions.getByType(OtelConverterExtension)
    def workingDirectory = extension.workingDirectory.get()
    def targetDirectory = extension.targetDirectory.asFile.get()
    // Convert each javaagent artifact
    project.configurations.named('javaagent').get().resolve().each {jarFile ->
      def artifactName = getArtifactName(jarFile.name)
      def artifactDir = workingDirectory.dir(artifactName).asFile
      println ">>> Coonverting javaagent $artifactName into $artifactDir"
      project.copy {
        from(project.zipTree(jarFile)) {
          include '**/*.class'
        }
        into artifactDir
      }
      def converter = new Converter(artifactDir, targetDirectory)
      artifactDir.traverse(
        type: FILES,
        nameFilter: ~/.*\.class$/
      ) {
        converter.convert(it)
      }
    }
  }

  static String getArtifactName(String jarName) {
    // Removing file extension
    String artifactName = jarName.endsWith('.jar') ? jarName.substring(0, jarName.length() - 4) : jarName
    // Removing qualifier
    def parts = artifactName.split('-')
    def reversedParts = parts.iterator().reverse()
    while (reversedParts.hasNext()) {
      def part = reversedParts.next()
      if (part =~ /[a-zA-Z]/) {
        reversedParts.remove()
      } else {
        break
      }
    }
    return reversedParts.reverse().join('-')
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

      // Check if there are references to write as muzzle class
      if (muzzleConverter.hasReferences()) {
        MuzzleGenerator.writeMuzzleClass(targetFolder, file.name, muzzleConverter.getReferences())
      }
    }
  }
}

interface OtelConverterExtension {
  /** Where to extract original javaagent class files */
  DirectoryProperty getWorkingDirectory()
  /** Where to store converted javaagent class files */
  DirectoryProperty getTargetDirectory()
}
