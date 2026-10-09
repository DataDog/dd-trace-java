package datadog.gradle.plugin.muzzle

import org.gradle.api.Project
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.VerificationType
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider

private const val MUZZLE_REPORT_TYPE = "muzzle-dependency-report"

internal fun AttributeContainer.muzzleReportAttributes(objects: ObjectFactory) {
  attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.VERIFICATION))
  attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType::class.java, MUZZLE_REPORT_TYPE))
}

/** Publishes the producer's output with its inferred task dependency. */
fun Project.publishMuzzleReport(report: Provider<RegularFile>) {
  configurations.consumable("muzzleReportElements") {
    attributes.muzzleReportAttributes(objects)
    outgoing.artifact(report)
  }
}
