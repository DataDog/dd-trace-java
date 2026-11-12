package datadog.gradle.plugin.muzzle

import org.gradle.api.Project
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.VerificationType
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider

private const val MUZZLE_REPORT_TYPE = "muzzle-dependency-report"
private const val SUPPORTED_VERSIONS_TYPE = "supported-versions"

internal fun AttributeContainer.muzzleReportAttributes(objects: ObjectFactory) =
  verificationAttributes(objects, MUZZLE_REPORT_TYPE)

internal fun AttributeContainer.supportedVersionsAttributes(objects: ObjectFactory) =
  verificationAttributes(objects, SUPPORTED_VERSIONS_TYPE)

private fun AttributeContainer.verificationAttributes(objects: ObjectFactory, type: String) {
  attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.VERIFICATION))
  attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType::class.java, type))
}

/** Publishes the producer's output with its inferred task dependency. */
fun Project.publishMuzzleReport(report: Provider<RegularFile>) =
  publishVerificationArtifact("muzzleReportElements", MUZZLE_REPORT_TYPE, report)

/** Publishes the producer's supported versions with its inferred task dependency. */
fun Project.publishSupportedVersions(report: Provider<RegularFile>) =
  publishVerificationArtifact("supportedVersionsElements", SUPPORTED_VERSIONS_TYPE, report)

private fun Project.publishVerificationArtifact(name: String, type: String, report: Provider<RegularFile>) {
  configurations.consumable(name) {
    attributes.verificationAttributes(objects, type)
    outgoing.artifact(report)
  }
}
