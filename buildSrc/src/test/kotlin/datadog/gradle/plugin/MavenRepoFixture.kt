package datadog.gradle.plugin

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarOutputStream

/**
 * Test fixture for creating and managing fake Maven repositories.
 * Provides utilities to create Maven artifacts with proper structure and metadata.
 *
 * The fake Maven repository is automatically created in the constructor.
 */
class MavenRepoFixture(projectDir: File) {

  /** The root directory of the fake Maven repository */
  val repoDir: File = File(projectDir, "fake-maven-repo").apply { mkdirs() }

  /**
   * Gets the repository URL for use in Gradle configuration.
   */
  val repoUrl: String
    get() = repoDir.toURI().toString()

  /**
   * Publishes versions to the fake Maven repository for the specified module.
   * If the module already exists, adds the new versions to the existing ones.
   * Creates the module directory if it doesn't exist.
   *
   * @param group Maven group ID
   * @param module Maven artifact ID
   * @param versions List of versions to publish (will be merged with existing versions)
   * @param jarContentBuilder Optional lambda to add entries to the JAR
   */
  fun publishVersions(
    group: String,
    module: String,
    versions: List<String>,
    jarContentBuilder: ((JarOutputStream) -> Unit)? = null
  ) {
    require(versions.isNotEmpty()) { "versions must not be empty" }
    val groupPath = group.replace('.', '/')
    val moduleDir = File(repoDir, "$groupPath/$module").apply { mkdirs() }

    // Create all version artifacts
    versions.forEach { version ->
      createMavenVersion(moduleDir, group, module, version, jarContentBuilder)
    }

    // Read existing versions from metadata and merge with new versions
    val metadataFile = File(moduleDir, "maven-metadata.xml")
    val existingVersions = if (metadataFile.exists()) {
      val content = metadataFile.readText()
      val versionRegex = "<version>([^<]+)</version>".toRegex()
      versionRegex.findAll(content).map { it.groupValues[1] }.toList()
    } else {
      emptyList()
    }

    // Merge and sort all versions
    val allVersions = (existingVersions + versions).distinct().sorted()
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
   * @param contentBuilder Optional lambda to add custom entries to the JAR
   */
  private fun createJar(
    path: Path,
    group: String,
    module: String,
    version: String,
    contentBuilder: ((JarOutputStream) -> Unit)? = null
  ) {
    JarOutputStream(path.toFile().outputStream()).use { jos ->
      // Add Maven metadata
      val pomProperties = """
        groupId=$group
        artifactId=$module
        version=$version
      """.trimIndent()

      val pomPropertiesPath = "META-INF/maven/$group/$module/pom.properties"
      jos.putNextEntry(java.util.jar.JarEntry(pomPropertiesPath))
      jos.write(pomProperties.toByteArray())
      jos.closeEntry()

      // Add custom content if provided
      contentBuilder?.invoke(jos)

      // Add manifest if not provided by contentBuilder
      val manifestEntry = java.util.jar.JarEntry("META-INF/MANIFEST.MF")
      jos.putNextEntry(manifestEntry)
      jos.write("Manifest-Version: 1.0\n".toByteArray())
      jos.closeEntry()
    }
  }
}
