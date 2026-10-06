package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.tasks.MuzzleAggregateReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/** Aggregates dependency reports published by instrumentation projects. */
class MuzzleReportAggregationPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create<MuzzleReportAggregationExtension>("muzzleReports")
    extension.reportFile.convention(project.layout.buildDirectory.file("muzzle-deps-results/muzzle.csv"))

    val aggregation = project.configurations.dependencyScope("muzzleReportAggregation")
    // Reuse the Java project's declared dependencies instead of inspecting its subprojects.
    project.pluginManager.withPlugin("java") {
      aggregation.configure {
        extendsFrom(project.configurations.getByName(IMPLEMENTATION_CONFIGURATION_NAME))
      }
    }

    val results = project.configurations.resolvable("aggregateMuzzleReportResults") {
      extendsFrom(aggregation.get())
      isTransitive = false
      attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
      }
    }

    val reports = results.map { configuration ->
      configuration.incoming.artifactView {
        // Select reports from the Java graph; plain Java stubs have no matching report variant.
        withVariantReselection()
        attributes.muzzleReportAttributes(project.objects)
      }.files
    }

    val report = project.tasks.register<MuzzleAggregateReportTask>("aggregateMuzzleReports") {
      versionReports.from(reports)
      versionsFile.convention(extension.reportFile)
    }

    project.tasks.register("mergeMuzzleReports") {
      group = "Muzzle"
      description = "Deprecated: use aggregateMuzzleReports for the aggregate dependency report"
      dependsOn(report)
      doFirst { logger.warn("mergeMuzzleReports is deprecated; use aggregateMuzzleReports") }
    }
  }
}
