package datadog.gradle.plugin.muzzle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal object MuzzleVersionSubstitutionSupport {
  fun applyTo(project: Project, configuration: Configuration, directive: MuzzleDirective) {
    val substitutions = directive.versionSubstitutions
    if (substitutions.isEmpty()) return

    val repoDir = materializeSubstitutionRepository(project, configuration, substitutions)
    project.repositories.maven {
      name = "${configuration.name}MuzzleSubstitutions"
      url = repoDir.toUri()
    }

    configuration.resolutionStrategy.eachDependency {
      val match = substitutions.firstOrNull { it.matches(requested.group, requested.name, requested.version) }
      if (match != null) {
        useTarget(match.targetNotation)
        because("Muzzle substituteVersion override for ${match.requestedNotation}")
      }
    }
  }

  private fun materializeSubstitutionRepository(
    project: Project,
    configuration: Configuration,
    substitutions: List<VersionSubstitution>
  ): Path {
    val repoDir = project.layout.buildDirectory
      .dir("generated/muzzle-version-substitutions/${configuration.name}")
      .get()
      .asFile
      .toPath()
    Files.createDirectories(repoDir)
    substitutions.forEach { materializeSubstitution(project, repoDir, it) }
    return repoDir
  }

  private fun materializeSubstitution(project: Project, repoDir: Path, substitution: VersionSubstitution) {
    val targetPom = resolveArtifactFile(project, "${substitution.targetNotation}@pom")
    val destinationDir = repoDir
      .resolve(substitution.requestedGroup.replace('.', '/'))
      .resolve(substitution.requestedModule)
      .resolve(substitution.requestedVersion)
    Files.createDirectories(destinationDir)
    val destinationPom = destinationDir.resolve("${substitution.requestedModule}-${substitution.requestedVersion}.pom")
    Files.copy(
      targetPom,
      destinationPom,
      StandardCopyOption.REPLACE_EXISTING
    )
    rewriteProjectVersion(destinationPom, substitution)

    resolveOptionalArtifactFile(project, substitution.targetNotation)?.let { artifactFile ->
      if (Files.isSameFile(targetPom, artifactFile)) {
        return@let
      }
      val ext = artifactFile.fileName.toString().substringAfterLast('.', "")
      val artifactName = "${substitution.requestedModule}-${substitution.requestedVersion}.${ext}"
      Files.copy(artifactFile, destinationDir.resolve(artifactName), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun resolveArtifactFile(project: Project, notation: String): Path =
    project.configurations.detachedConfiguration(project.dependencies.create(notation)).apply {
      isTransitive = false
    }.singleFile.toPath()

  private fun resolveOptionalArtifactFile(project: Project, notation: String): Path? =
    project.configurations.detachedConfiguration(project.dependencies.create(notation)).apply {
      isTransitive = false
    }.resolvedConfiguration.lenientConfiguration
      .artifacts
      .singleOrNull()
      ?.file
      ?.toPath()

  private fun rewriteProjectVersion(pomFile: Path, substitution: VersionSubstitution) {
    val artifactIdLine = "<artifactId>${substitution.targetModule}</artifactId>"
    val versionLine = "<version>${substitution.targetVersion}</version>"
    val replacementVersionLine = "<version>${substitution.requestedVersion}</version>"
    val pattern = Regex("${Regex.escape(artifactIdLine)}\\s*${Regex.escape(versionLine)}")
    val content = pomFile.readText()
    val rewritten = content.replaceFirst(pattern, "$artifactIdLine\n  $replacementVersionLine")
    check(rewritten != content) {
      "Could not rewrite version '${substitution.targetVersion}' to '${substitution.requestedVersion}' in POM for ${substitution.targetNotation}"
    }
    pomFile.writeText(rewritten)
  }
}
