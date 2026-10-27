import datadog.buildlogic.ci.CiBuilderInputsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.withType

val ciBuilderInputs = extensions.create<CiBuilderInputsExtension>("ciBuilderInputs")

tasks.withType<Test>().configureEach {
  ciBuilderInputs.track(this)
}
