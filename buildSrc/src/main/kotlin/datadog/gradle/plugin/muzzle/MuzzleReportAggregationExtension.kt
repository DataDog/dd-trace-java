package datadog.gradle.plugin.muzzle

import org.gradle.api.file.RegularFileProperty

abstract class MuzzleReportAggregationExtension {
  abstract val reportFile: RegularFileProperty
}
