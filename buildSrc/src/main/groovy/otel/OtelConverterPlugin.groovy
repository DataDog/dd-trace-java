package otel

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import otel.muzzle.MuzzleConverter
import otel.muzzle.MuzzleGenerator

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static groovy.io.FileType.FILES
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES

@SuppressWarnings('unused')
class OtelConverterPlugin implements Plugin<Project> {
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
    def buildTask = project.tasks.named('classes').get()
    buildTask.dependsOn(convertTask)
  }
}

class ConvertJavaAgent extends DefaultTask {
  @TaskAction
  void convert() {
    // Retrieve plugin configuration
    def extension = project.extensions.getByType(OtelConverterExtension)
    def workingDirectory = extension.workingDirectory.get()
    def targetDirectory = extension.targetDirectory.asFile.get()
    // Convert each javaagent artifact
    project.configurations.named('javaagent').get().resolve().each {jarFile ->
      def artifactName = getArtifactName(jarFile.name)
      def artifactDir = workingDirectory.dir(artifactName).asFile
      def instrumenterFiles = []
      logger.debug("Coonverting javaagent $artifactName into $artifactDir")
      project.copy {
        from(project.zipTree(jarFile)) {
          include '**/*.class'
        }
        into artifactDir
      }
      artifactDir.traverse(
        type: FILES,
        nameFilter: ~/.*\.class$/
      ) {
        convertClass(artifactDir, targetDirectory, it, instrumenterFiles)
      }
      generateServiceLoaderConfiguration(targetDirectory, instrumenterFiles)
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

  void convertClass(File sourceDirectory, File targetDirectory, File file, List<String> instrumenterFiles) {
    // Compute target file location
    def relativePath = sourceDirectory.relativePath(file)
    Path targetFile = targetDirectory.toPath().resolve(relativePath)
    Path targetFolder = targetFile.parent
    logger.debug("Converting class file ${relativePath}")
    // Ensure target folder exists
    if (!Files.isDirectory(targetFolder)) {
      Files.createDirectories(targetFolder)
    }

    try (InputStream inputStream = Files.newInputStream(file.toPath())) {
      def writer = new ClassWriter(COMPUTE_FRAMES)
      def virtualFieldConverter = new VirtualFieldConverter(writer, file.name)
      def muzzleConverter = new MuzzleConverter(virtualFieldConverter, file.name)
      def instrumentationModuleConverter = new InstrumentationModuleConverter(muzzleConverter, file.name)
      def typeInstrumentationConverter = new TypeInstrumentationConverter(instrumentationModuleConverter, file.name)
      def javaAgentApiChecker = new OtelApiVerifier(typeInstrumentationConverter, file.name)
      def reader = new ClassReader(inputStream)
      reader.accept(javaAgentApiChecker, 0)
      Files.write(targetFile, writer.toByteArray())

      // Check if there are references to write as muzzle class
      if (muzzleConverter.isInstrumentationModule()) {
        MuzzleGenerator.writeMuzzleClass(targetFolder, relativePath, muzzleConverter.getReferences())
        instrumenterFiles.add(relativePath)
      }
    }
  }

  void generateServiceLoaderConfiguration(File targetDirectory, List<String> instrumenterFiles) {
    if (instrumenterFiles.isEmpty()) {
      return
    }
    def classNames = instrumenterFiles.collect {
      it.replace('/', '.').substring(0, it.length() - 6)
    }
    logger.debug("Writing service loader configuration for instrumenters: ${classNames.join(',')}")
    def configurationPath = targetDirectory.toPath().resolve("META-INF/services/datadog.trace.agent.tooling.Instrumenter")
    Files.createDirectories(configurationPath.parent)
    Files.write(configurationPath, classNames, StandardCharsets.UTF_8)
  }
}

interface OtelConverterExtension {
  /** Where to extract original javaagent class files */
  DirectoryProperty getWorkingDirectory()
  /** Where to store converted javaagent class files */
  DirectoryProperty getTargetDirectory()
}
