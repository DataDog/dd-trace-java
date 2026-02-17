package datadog.gradle.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildResultException
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

      val jarFile = File(versionDir, "$module-$version.jar")
      createJar(jarFile.toPath(), group, module, version, jarContentBuilder)
      writeChecksum(jarFile)
    }

    val metadataFile = File(moduleDir, "maven-metadata.xml")
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
          <lastUpdated>20260216120000</lastUpdated>
        </versioning>
      </metadata>
      """.trimIndent()
    )
    writeChecksum(metadataFile)

    return repoDir
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
