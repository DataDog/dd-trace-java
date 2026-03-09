package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

class MuzzleVersionSubstitutionSupportTest {

  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `substituteVersion rewrites missing module version during resolution`() {
    val project = ProjectBuilder.builder()
      .withProjectDir(tempDir.resolve("project").toFile())
      .withName("test-project")
      .build()

    val repoDir = tempDir.resolve("repo")
    writeModule(repoDir, "org.example", "demo", "1.0")

    project.repositories.maven {
      url = repoDir.toUri()
    }

    val directive = MuzzleDirective().apply {
      substituteVersion("org.example:demo:2.0", "org.example:demo:1.0")
    }

    val configuration = project.configurations.create("muzzleTest") {
      isCanBeResolved = true
      isCanBeConsumed = false
      MuzzleVersionSubstitutionSupport.applyTo(project, this, directive)
    }
    project.dependencies.add(configuration.name, "org.example:demo:2.0")

    val resolved = configuration.resolvedConfiguration.resolvedArtifacts.single()
    assertThat(resolved.moduleVersion.id.group).isEqualTo("org.example")
    assertThat(resolved.name).isEqualTo("demo")
    assertThat(resolved.moduleVersion.id.version).isEqualTo("1.0")
  }

  @Test
  fun `substituteVersion supports multiple substitutions per directive`() {
    val project = ProjectBuilder.builder()
      .withProjectDir(tempDir.resolve("project").toFile())
      .withName("test-project")
      .build()

    val repoDir = tempDir.resolve("repo")
    writeModule(repoDir, "org.example", "alpha", "1.0")
    writeModule(repoDir, "org.example", "beta", "1.0")

    project.repositories.maven {
      url = repoDir.toUri()
    }

    val directive = MuzzleDirective().apply {
      substituteVersion("org.example:alpha:2.0", "org.example:alpha:1.0")
      substituteVersion("org.example:beta:2.0", "org.example:beta:1.0")
    }

    val alphaConfig = project.configurations.create("muzzleTestAlpha") {
      isCanBeResolved = true
      isCanBeConsumed = false
      MuzzleVersionSubstitutionSupport.applyTo(project, this, directive)
    }
    project.dependencies.add(alphaConfig.name, "org.example:alpha:2.0")
    val alphaResolved = alphaConfig.resolvedConfiguration.resolvedArtifacts.single()
    assertThat(alphaResolved.moduleVersion.id.version).isEqualTo("1.0")

    val betaConfig = project.configurations.create("muzzleTestBeta") {
      isCanBeResolved = true
      isCanBeConsumed = false
      MuzzleVersionSubstitutionSupport.applyTo(project, this, directive)
    }
    project.dependencies.add(betaConfig.name, "org.example:beta:2.0")
    val betaResolved = betaConfig.resolvedConfiguration.resolvedArtifacts.single()
    assertThat(betaResolved.moduleVersion.id.version).isEqualTo("1.0")
  }

  @Test
  fun `substituteVersion preserves rewritten pom for pom-only module`() {
    val project = ProjectBuilder.builder()
      .withProjectDir(tempDir.resolve("project").toFile())
      .withName("test-project")
      .build()

    val repoDir = tempDir.resolve("repo")
    writePomOnlyModule(repoDir, "org.example", "bom", "1.0")

    project.repositories.maven {
      url = repoDir.toUri()
    }

    val directive = MuzzleDirective().apply {
      substituteVersion("org.example:bom:2.0", "org.example:bom:1.0")
    }

    val configuration = project.configurations.create("muzzleTestPomOnly") {
      isCanBeResolved = true
      isCanBeConsumed = false
      MuzzleVersionSubstitutionSupport.applyTo(project, this, directive)
    }

    val generatedPom = tempDir.resolve(
      "project/build/generated/muzzle-version-substitutions/" +
        "muzzleTestPomOnly/org/example/bom/2.0/bom-2.0.pom"
    )
    assertThat(generatedPom.readText()).contains("<version>2.0</version>")

    project.dependencies.add(configuration.name, "org.example:bom:2.0@pom")
    val resolved = configuration.singleFile.toPath()
    assertThat(resolved.readText()).contains("<version>2.0</version>")
  }

  @Test
  fun `substituteVersion validates coordinate format`() {
    val directive = MuzzleDirective()

    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      directive.substituteVersion("org.example:demo", "org.example:demo:1.0")
    }
  }

  private fun writeModule(repoDir: Path, group: String, module: String, version: String) {
    val moduleDir = repoDir.resolve(group.replace('.', '/')).resolve(module).resolve(version).createDirectories()
    moduleDir.resolve("$module-$version.pom").writeText(
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>$group</groupId>
        <artifactId>$module</artifactId>
        <version>$version</version>
      </project>
      """.trimIndent()
    )
    ZipOutputStream(moduleDir.resolve("$module-$version.jar").outputStream()).use { }
  }

  private fun writePomOnlyModule(repoDir: Path, group: String, module: String, version: String) {
    val moduleDir = repoDir.resolve(group.replace('.', '/')).resolve(module).resolve(version).createDirectories()
    moduleDir.resolve("$module-$version.pom").writeText(
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>$group</groupId>
        <artifactId>$module</artifactId>
        <version>$version</version>
        <packaging>pom</packaging>
      </project>
      """.trimIndent()
    )
  }
}
