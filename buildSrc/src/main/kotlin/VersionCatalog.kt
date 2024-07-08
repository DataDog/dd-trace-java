import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal fun Project.versionCatalog(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.getLibraryFromVersionCatalog(alias: String): Provider<MinimalExternalModuleDependency> {
  return versionCatalog().findLibrary(alias).orElseThrow { IllegalStateException("Library $alias not found in version catalog") }
}

internal fun Project.getBundleFromVersionCatalog(alias: String): Provider<ExternalModuleDependencyBundle> {
  return versionCatalog().findBundle(alias).orElseThrow { IllegalStateException("Bundle $alias not found in version catalog") }
}
