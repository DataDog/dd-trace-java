import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.jvm.tasks.Jar
import org.vafer.jdependency.Clazz
import org.vafer.jdependency.Clazzpath
import org.vafer.jdependency.ClazzpathUnit

import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MinimizePlugin implements Plugin<Project> {

  @Override
  void apply(Project project) {
    def extension = project.extensions.create('minimize', MinimizePluginExtension)

    Jar shadowJarTask = project.tasks['shadowJar']

    def minShadowJarTask = project.task('minShadowJar', type: MinimizeJarTask) {
      group = 'build'
      description = 'Filters out unused classes from shadowJar'

      archiveClassifier.set 'minimized'
      unusedClassFiles.set(extension.additionalInclusions)

      dependsOn shadowJarTask
    }

    // Separate configuration task to work-around the fact
    // that zipTree eagerly evaluates the shadowJarTask.archiveFile
    project.task('configureMinShadowJar') { configureMinShadowJarTask ->
      Provider<RegularFile> shadowJarFile = shadowJarTask.archiveFile

      doFirst {
        List<ClazzpathUnit> ddUnits = []
        def clazzpath = new Clazzpath()

        Set<String> firstLevelFiles =
          filesOfFirstLevelDatadogDependencies(project, shadowJarTask)

        List<Pattern> additionalInclusions = extension.additionalInclusions.get()

        // inspect the original shadow jar
        project.zipTree(shadowJarFile).visit { FileVisitDetails fdet ->
          def relPath = fdet.relativePath as String
          if (!fdet.directory && relPath =~ /\.class$/) {
            // create temporary zip file with just this class to workaround
            // jdependency API limitations
            ByteArrayOutputStream baos = new ByteArrayOutputStream()
            new ZipOutputStream(baos).withCloseable { zos ->
              ZipEntry entry = new ZipEntry(relPath)
              zos.putNextEntry(entry)
              fdet.file.withInputStream {is ->
                zos << is
              }
              zos.closeEntry()
            }

            ClazzpathUnit unit = clazzpath.addClazzpathUnit(
              new ByteArrayInputStream(baos.toByteArray()), relPath)

            // two criteria for inclusion:
            // 1. path of class contains datadog in some first
            // 2. the path can be found in the jars of shadowJar direct dependencies
            //    whose names includes 'datadog'. Other inputs of the shadowJar are
            //    not considered
            // 3. If the path matches any of additional inclusions
            if (relPath =~ /^com\/datadoghq\b/ || relPath =~ /^com\/datadog\b/
              || relPath =~ /^datadog\// || relPath in firstLevelFiles ||
              additionalInclusions.any {relPath =~ it}) {
              ddUnits << unit
            }
          }
        }

        Set<Clazz> unused = clazzpath.clazzes
        ddUnits.each {
          unused.removeAll(it.clazzes)
          unused.removeAll(it.transitiveDependencies)
        }

        minShadowJarTask.configure {
          from(project.zipTree(shadowJarFile)) {
            exclude unused.collect { "${it.toString().replaceAll(/\./, '/')}.class" }
          }
        }
      }

      dependsOn shadowJarTask
      minShadowJarTask.dependsOn configureMinShadowJarTask
    }
  }

  private Set<String> filesOfFirstLevelDatadogDependencies(Project project, Jar shadowJarTask) {
    shadowJarTask.configurations.collectMany {
      it.resolvedConfiguration.firstLevelModuleDependencies
    }.findAll {
      it.name =~ /datadog/
    }.collectMany {
      it.moduleArtifacts*.file
    }.collectMany {
      def files = [] as Set
      project.zipTree(it).visit { FileVisitDetails det ->
        if (!det.directory) {
          files << det.relativePath.toString()
        }
      }
      files
    } as Set
  }
}

/* Extends Jar task and adds an input property to avoid the minimized jar
 * being considered up-to-date even after changing the additionInclusions
 * extension property */
class MinimizeJarTask extends Jar {
  @Input
  final ListProperty<Pattern> unusedClassFiles

  MinimizeJarTask() {
    ObjectFactory objectFactory = project.objects
    unusedClassFiles = objectFactory.listProperty(Pattern)
    unusedClassFiles.convention([])
  }
}

class MinimizePluginExtension {
  // includes dependencies thereof
  ListProperty<Pattern> additionalInclusions

  MinimizePluginExtension(ObjectFactory objects) {
    additionalInclusions = objects.listProperty(Pattern)
  }
}
