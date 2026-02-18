package datadog.gradle.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.intellij.lang.annotations.Language
import org.w3c.dom.Document
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Base fixture for Gradle plugin integration tests.
 * Provides common functionality for setting up test projects and running Gradle builds.
 */
internal open class GradleFixture(
  protected val projectDir: File,
) {
  /**
   * Runs Gradle with the specified arguments.
   *
   * @param args Gradle task names and arguments
   * @param expectFailure Whether the build is expected to fail
   * @param env Environment variables to set (merged with system environment)
   * @return The build result
   */
  fun run(vararg args: String, expectFailure: Boolean = false, env: Map<String, String> = emptyMap()): BuildResult {
    val runner = GradleRunner.create()
      .withTestKitDir(File(projectDir, ".gradle-test-kit"))
      .withPluginClasspath()
      .withProjectDir(projectDir)
      .withEnvironment(System.getenv() + env)
      .withArguments(*args)
    return try {
      if (expectFailure) runner.buildAndFail() else runner.build()
    } catch (e: UnexpectedBuildResultException) {
      e.buildResult
    }
  }

  /**
   * Adds a subproject to the build.
   * Updates settings.gradle and creates the build script for the subproject.
   *
   * @param projectPath The project path (e.g., "dd-java-agent:instrumentation:other")
   * @param buildScript The build script content for the subproject
   */
  fun addSubproject(projectPath: String, @Language("Groovy") buildScript: String) {
    // Add to settings.gradle
    val settingsFile = file("settings.gradle")
    if (settingsFile.exists()) {
      settingsFile.appendText("\ninclude ':$projectPath'")
    } else {
      settingsFile.writeText("include ':$projectPath'")
    }

    file("${projectPath.replace(':', '/')}/build.gradle")
      .writeText(buildScript.trimIndent())
  }

  /**
   * Writes the root project's build.gradle file.
   *
   * @param buildScript The build script content for the root project
   */
  fun writeRootProject(@Language("Groovy") buildScript: String) {
    file("build.gradle").writeText(buildScript.trimIndent())
  }

  /**
   * Parses an XML file into a DOM Document.
   */
  fun parseXml(xmlFile: File): Document {
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    return builder.parse(xmlFile)
  }

  /**
   * Creates or gets a file in the project directory, ensuring parent directories exist.
   */
  protected fun file(path: String): File =
    File(projectDir, path).also { file ->
      file.parentFile?.mkdirs()
    }

  /**
   * Creates a fake local Maven repository with the specified artifacts and versions.
   * Generates proper POM files, JAR files, maven-metadata.xml, and checksums.
   *
   * @param group Maven group ID
   * @param module Maven artifact ID
   * @param versions List of versions to create
   * @param jarContentBuilder Optional lambda to add entries to the JAR
   * @return The repository root directory
   */
  fun createFakeMavenRepo(
    group: String,
    module: String,
    versions: List<String>,
    jarContentBuilder: ((JarOutputStream) -> Unit)? = null
  ): File {
    require(versions.isNotEmpty()) { "versions must not be empty" }
    val repoDir = File(projectDir, "fake-maven-repo").apply { mkdirs() }
    val groupPath = group.replace('.', '/')
    val moduleDir = File(repoDir, "$groupPath/$module").apply { mkdirs() }

    versions.forEach { version ->
      createMavenVersion(moduleDir, group, module, version, jarContentBuilder)
    }

    val metadataFile = File(moduleDir, "maven-metadata.xml")
    writeMavenMetadata(metadataFile, group, module, versions)

    return repoDir
  }

  /**
   * Adds a new version to an existing fake Maven repository.
   * Updates the maven-metadata.xml to include the new version.
   *
   * @param repoDir The root directory of the fake Maven repository
   * @param group Maven group ID
   * @param module Maven artifact ID
   * @param version Version to add
   * @param jarContentBuilder Optional lambda to add entries to the JAR
   */
  fun addVersionToFakeMavenRepo(
    repoDir: File,
    group: String,
    module: String,
    version: String,
    jarContentBuilder: ((JarOutputStream) -> Unit)? = null
  ) {
    val groupPath = group.replace('.', '/')
    val moduleDir = File(repoDir, "$groupPath/$module")
    require(moduleDir.exists()) { "Module directory does not exist: $moduleDir" }

    // Create version artifacts (POM + JAR with checksums)
    createMavenVersion(moduleDir, group, module, version, jarContentBuilder)

    // Read existing versions from metadata
    val metadataFile = File(moduleDir, "maven-metadata.xml")
    val existingVersions = if (metadataFile.exists()) {
      val content = metadataFile.readText()
      val versionRegex = "<version>([^<]+)</version>".toRegex()
      versionRegex.findAll(content).map { it.groupValues[1] }.toList()
    } else {
      emptyList()
    }

    // Add new version and update metadata
    val allVersions = (existingVersions + version).distinct().sorted()
    writeMavenMetadata(metadataFile, group, module, allVersions)
  }

  /**
   * Creates a single Maven version with POM and JAR artifacts (including checksums).
   *
   * @param moduleDir The module directory (e.g., repo/com/example/artifact)
   * @param group Maven group ID
   * @param module Maven artifact ID
   * @param version Version to create
   * @param jarContentBuilder Optional lambda to add entries to the JAR
   */
  private fun createMavenVersion(
    moduleDir: File,
    group: String,
    module: String,
    version: String,
    jarContentBuilder: ((JarOutputStream) -> Unit)? = null
  ) {
    val versionDir = File(moduleDir, version).apply { mkdirs() }

    // Create POM file
    val pomFile = File(versionDir, "$module-$version.pom")
    pomFile.writeText(
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0"
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>
        <groupId>$group</groupId>
        <artifactId>$module</artifactId>
        <version>$version</version>
        <packaging>jar</packaging>
      </project>
      """.trimIndent()
    )
    writeChecksum(pomFile)

    // Create JAR file
    val jarFile = File(versionDir, "$module-$version.jar")
    createJar(jarFile.toPath(), group, module, version, jarContentBuilder)
    writeChecksum(jarFile)
  }

  /**
   * Writes maven-metadata.xml for a module with the given versions.
   *
   * @param metadataFile The maven-metadata.xml file to write
   * @param group Maven group ID
   * @param module Maven artifact ID
   * @param versions List of versions (should be sorted)
   */
  private fun writeMavenMetadata(
    metadataFile: File,
    group: String,
    module: String,
    versions: List<String>
  ) {
    metadataFile.writeText(
      """
      <metadata>
        <groupId>$group</groupId>
        <artifactId>$module</artifactId>
        <versioning>
          <latest>${versions.last()}</latest>
          <release>${versions.last()}</release>
          <versions>
      ${versions.joinToString("\n") { "      <version>$it</version>" }}
          </versions>
          <lastUpdated>${System.currentTimeMillis() / 1000}</lastUpdated>
        </versioning>
      </metadata>
      """.trimIndent()
    )
    writeChecksum(metadataFile)
  }

  /**
   * Generates SHA-1 and MD5 checksum files for a given file.
   */
  private fun writeChecksum(file: File) {
    val content = file.readBytes()
    val sha1 = MessageDigest.getInstance("SHA-1").digest(content)
      .joinToString("") { "%02x".format(it) }
    File(file.parentFile, "${file.name}.sha1").writeText(sha1)

    val md5 = MessageDigest.getInstance("MD5").digest(content)
      .joinToString("") { "%02x".format(it) }
    File(file.parentFile, "${file.name}.md5").writeText(md5)
  }

  /**
   * Creates a JAR file at the specified path with standard Maven metadata, optionally with custom content.
   *
   * @param path Path where the JAR should be created
   * @param group Maven group ID
   * @param module Maven artifact ID
   * @param version Maven version
   * @param contentBuilder Optional lambda to add additional entries to the JAR
   */
  private fun createJar(
    path: Path,
    group: String,
    module: String,
    version: String,
    contentBuilder: ((JarOutputStream) -> Unit)? = null
  ) {
    JarOutputStream(path.toFile().outputStream()).use { jos ->
      // Add standard Maven metadata files
      val metadataPath = "META-INF/maven/$group/$module"

      // Add pom.properties
      jos.putNextEntry(java.util.zip.ZipEntry("$metadataPath/pom.properties"))
      jos.write("groupId=$group\nartifactId=$module\nversion=$version\n".toByteArray())
      jos.closeEntry()

      // Add pom.xml
      jos.putNextEntry(java.util.zip.ZipEntry("$metadataPath/pom.xml"))
      jos.write(
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>$group</groupId>
          <artifactId>$module</artifactId>
          <version>$version</version>
        </project>
        """.trimIndent().toByteArray()
      )
      jos.closeEntry()

      // Add any custom content
      contentBuilder?.invoke(jos)
    }
  }
}
