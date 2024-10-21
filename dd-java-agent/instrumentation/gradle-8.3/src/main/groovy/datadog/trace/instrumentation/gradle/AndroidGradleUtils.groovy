package datadog.trace.instrumentation.gradle

import datadog.trace.api.civisibility.domain.BuildModuleLayout
import datadog.trace.api.civisibility.domain.SourceSet
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.testing.Test

class AndroidGradleUtils {

  private static final Logger LOGGER = Logging.getLogger(AndroidGradleUtils.class)

  static BuildModuleLayout getAndroidModuleLayout(Project project, Test task) {
    try {
      def variant = getVariant(project, task)
      if (variant == null) {
        return null
      }

      def sources = getSources(variant)
      def destinations = getDestinations(variant, project)
      return new BuildModuleLayout(Collections.singletonList(new SourceSet(SourceSet.Type.CODE, sources, destinations)))
    } catch (Exception e) {
      LOGGER.error("Could not get Android module layout for ${project.name} and ${task.path}", e)
      return null
    }
  }

  private static getVariant(Project project, Test task) {
    def androidPlugin = project.plugins.findPlugin('android') ?: project.plugins.findPlugin('android-library')

    def variants
    if (androidPlugin.class.simpleName == 'LibraryPlugin') {
      variants = project.android.libraryVariants
    } else {
      variants = project.android.applicationVariants
    }

    for (def v : variants) {
      if (task.path.endsWith("test${v.name.capitalize()}UnitTest")) {
        return v
      }
    }
    return null
  }

  static Collection<File> getSources(variant) {
    List<File> sources = []
    for (def srcs : variant.sourceSets.java.srcDirs) {
      sources.addAll(srcs)
    }
    return sources
  }

  private static final def EXCLUDES = [
    'android/databinding/**/*.class',
    '**/android/databinding/*Binding.class',
    '**/BR.*',
    '**/R.class',
    '**/R$*.class',
    '**/BuildConfig.*',
    '**/Manifest*.*',
    '**/*$ViewInjector*.*',
    '**/*$ViewBinder*.*',
    '**/*_MembersInjector.class',
    '**/Dagger*Component.class',
    '**/Dagger*Component$Builder.class',
    '**/*Module_*Factory.class'
  ]

  private static Collection<File> getDestinations(variant, Project project) {
    def javaDestinations = getJavaDestinations(variant)
    FileTree javaTree = project.fileTree(dir: javaDestinations, excludes: EXCLUDES)

    FileTree destinationsTree
    if (project.plugins.findPlugin('kotlin-android') != null) {
      def kotlinDestinations = "${project.buildDir}/tmp/kotlin-classes/${variant.name}"
      def kotlinTree = project.fileTree(dir: kotlinDestinations, excludes: EXCLUDES)
      destinationsTree = javaTree + kotlinTree
    } else {
      destinationsTree = javaTree
    }
    return destinationsTree.files
  }

  private static def getJavaDestinations(variant) {
    if (variant.hasProperty('javaCompileProvider')) {
      return variant.javaCompileProvider.get().destinationDir
    } else {
      return variant.javaCompile.destinationDir
    }
  }
}
