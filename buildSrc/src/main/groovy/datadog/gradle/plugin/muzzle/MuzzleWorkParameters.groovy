package datadog.gradle.plugin.muzzle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.workers.WorkParameters

interface MuzzleWorkParameters extends WorkParameters {
  Property<Long> getBuildStartedTime()

    ConfigurableFileCollection getBootstrapClassPath()

  ConfigurableFileCollection getToolingClassPath()

  ConfigurableFileCollection getInstrumentationClassPath()

  ConfigurableFileCollection getTestApplicationClassPath()

  Property<Boolean> getAssertPass()

  Property<String> getMuzzleDirective()
}
