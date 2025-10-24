import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.*

apply(plugin = "java-library")

abstract class TestJvmConstraintExtension @javax.inject.Inject constructor(
  private val taskName: String,
  private val objects: ObjectFactory,
  private val providers: ProviderFactory,
  private val project: Project,
) {
  val minJavaVersionForTests = objects.property<JavaVersion>()
    .convention(
      providers.provider { project.extra["${taskName}MinJavaVersionForTests"] as JavaVersion }.orElse(
        providers.provider { project.extra["minJavaVersionForTests"] as JavaVersion }
      )
    )
  val maxJavaVersionForTests = objects.property<JavaVersion>()
    .convention(
      providers.provider { project.extra["${taskName}MaxJavaVersionForTests"] as JavaVersion }.orElse(
        providers.provider { project.extra["maxJavaVersionForTests"] as JavaVersion }
      )
    )
  val forceJdk = objects.listProperty<String>().convention(emptyList())
    .convention(providers.provider { project.extra["forceJdk"] as List<String> })
  val excludeJdk = objects.listProperty<String>().convention(emptyList())
    .convention(providers.provider { project.extra["excludeJdk"] as List<String> })

  companion object {
    const val NAME = "jvmConstraint"
  }
}


tasks.withType<Test>().configureEach {
  if (extensions.findByName(TestJvmConstraintExtension.NAME) != null) {
    return@configureEach
  }

  val extension = project.objects.newInstance<TestJvmConstraintExtension>(name, project.objects, project.providers, project)

  // todo add testJvm ?

  inputs.property("jvmConstraint.minJavaVersionForTests", extension.minJavaVersionForTests)
  inputs.property("jvmConstraint.maxJavaVersionForTests", extension.maxJavaVersionForTests)
  inputs.property("jvmConstraint.skipSettingTestJavaVersion", extension.skipSettingTestJavaVersion)
  inputs.property("jvmConstraint.forceJdk", extension.forceJdk)
  inputs.property("jvmConstraint.excludeJdk", extension.excludeJdk)

  extensions.add(TestJvmConstraintExtension.NAME, extension)
}



