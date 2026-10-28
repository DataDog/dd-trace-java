import datadog.buildlogic.ci.CiBuilderInputsExtension
import datadog.buildlogic.smoketest.NestedGradleBuild
import datadog.buildlogic.smoketest.NestedMavenBuild
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.withType

val ciBuilderInputs = extensions.create<CiBuilderInputsExtension>("ciBuilderInputs")

tasks.withType<Test>().configureEach {
  ciBuilderInputs.track(this)
}

tasks.withType<NestedGradleBuild>().configureEach {
  ciBuilderInputs.track(this)
}

tasks.withType<NestedMavenBuild>().configureEach {
  ciBuilderInputs.track(this)
}
