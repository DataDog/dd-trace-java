package datadog.gradle.plugin.muzzle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters

interface MuzzleWorkParameters : WorkParameters {
    val buildStartedTime: Property<Long>
    val bootstrapClassPath: ConfigurableFileCollection
    val toolingClassPath: ConfigurableFileCollection
    val instrumentationClassPath: ConfigurableFileCollection
    val testApplicationClassPath: ConfigurableFileCollection
    val assertPass: Property<Boolean>
    val muzzleDirective: Property<String>
    val resultFile: RegularFileProperty
}

