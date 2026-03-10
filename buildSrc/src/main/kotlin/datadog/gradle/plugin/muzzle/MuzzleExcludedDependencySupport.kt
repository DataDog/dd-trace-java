package datadog.gradle.plugin.muzzle

import org.eclipse.aether.artifact.Artifact
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

internal object MuzzleExcludedDependencySupport {
  fun applyTo(
    project: Project,
    configuration: Configuration,
    directive: MuzzleDirective,
    versionArtifact: Artifact?
  ) {
    if (versionArtifact == null || directive.excludedDependencies.isEmpty()) {
      return
    }

    val repositoryUris = directive.getRepositories(MuzzleMavenRepoUtils.MUZZLE_REPOS).map { URI.create(it.url) }
    val repoDir = project.layout.buildDirectory
      .dir("generated/muzzle-excluded-dependencies/${configuration.name}")
      .get()
      .asFile
      .toPath()
    Files.createDirectories(repoDir)

    materializeArtifactHierarchy(
      repoDir = repoDir,
      repositories = repositoryUris,
      coordinates = Coordinates(versionArtifact.groupId, versionArtifact.artifactId, versionArtifact.version),
      excludedDependencies = directive.excludedDependencies.map { it.split(":", limit = 2) }.map { it[0] to it[1] }.toSet(),
      rootArtifact = versionArtifact,
      seen = LinkedHashSet()
    )

    val generatedRepo = project.repositories.maven {
      name = "${configuration.name}MuzzleExcludedDependencies"
      url = repoDir.toUri()
    }
    project.repositories.remove(generatedRepo)
    project.repositories.addFirst(generatedRepo)
  }

  private fun materializeArtifactHierarchy(
    repoDir: Path,
    repositories: List<URI>,
    coordinates: Coordinates,
    excludedDependencies: Set<Pair<String, String>>,
    rootArtifact: Artifact?,
    seen: MutableSet<Coordinates>
  ) {
    if (!seen.add(coordinates)) {
      return
    }

    val pomBytes = downloadRequired(repositories, coordinates.pomRelativePath)
    val pomDocument = parsePom(pomBytes)
    removeExcludedDependencies(pomDocument, excludedDependencies)
    writePom(repoDir.resolve(coordinates.pomRelativePath), pomDocument)

    if (rootArtifact != null) {
      downloadOptional(repositories, coordinates.artifactRelativePath(rootArtifact.extension, rootArtifact.classifier))
        ?.let { artifactBytes ->
          val artifactPath = repoDir.resolve(coordinates.artifactRelativePath(rootArtifact.extension, rootArtifact.classifier))
          Files.createDirectories(artifactPath.parent)
          Files.write(artifactPath, artifactBytes)
        }
    }

    parseParentCoordinates(pomDocument)?.let { parent ->
      materializeArtifactHierarchy(
        repoDir = repoDir,
        repositories = repositories,
        coordinates = parent,
        excludedDependencies = excludedDependencies,
        rootArtifact = null,
        seen = seen
      )
    }
  }

  private fun parsePom(bytes: ByteArray): Document =
    DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

  private fun parseParentCoordinates(document: Document): Coordinates? {
    val project = document.documentElement ?: return null
    val parent = childElements(project).firstOrNull { it.tagName == "parent" } ?: return null
    val groupId = childText(parent, "groupId") ?: return null
    val artifactId = childText(parent, "artifactId") ?: return null
    val version = childText(parent, "version") ?: return null
    return Coordinates(groupId, artifactId, version)
  }

  private fun removeExcludedDependencies(document: Document, excludedDependencies: Set<Pair<String, String>>) {
    if (excludedDependencies.isEmpty()) {
      return
    }

    val dependencies = document.getElementsByTagName("dependency")
    val toRemove = mutableListOf<Element>()
    for (index in 0 until dependencies.length) {
      val dependency = dependencies.item(index) as? Element ?: continue
      val groupId = childText(dependency, "groupId") ?: continue
      val artifactId = childText(dependency, "artifactId") ?: continue
      if (excludedDependencies.contains(groupId to artifactId)) {
        toRemove.add(dependency)
      }
    }

    toRemove.forEach { dependency ->
      dependency.parentNode?.removeChild(dependency)
    }
  }

  private fun writePom(path: Path, document: Document) {
    Files.createDirectories(path.parent)
    TransformerFactory.newInstance().newTransformer().apply {
      setOutputProperty(OutputKeys.INDENT, "yes")
      setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }.transform(DOMSource(document), StreamResult(path.toFile()))
  }

  private fun downloadRequired(repositories: List<URI>, relativePath: String): ByteArray =
    downloadOptional(repositories, relativePath)
      ?: error("Could not download $relativePath from repositories ${repositories.joinToString()}")

  private fun downloadOptional(repositories: List<URI>, relativePath: String): ByteArray? {
    for (repository in repositories) {
      try {
        val resolvedUri = URI.create("${repository.toString().trimEnd('/')}/$relativePath")
        val bytes = when (resolvedUri.scheme) {
          "file" -> {
            val path = Path.of(resolvedUri)
            if (Files.exists(path)) Files.readAllBytes(path) else null
          }
          "http", "https" -> resolvedUri.toURL().openStream().use { it.readBytes() }
          else -> null
        }
        if (bytes != null) {
          return bytes
        }
      } catch (_: FileNotFoundException) {
        // Try the next repository until one has the requested artifact.
      }
    }
    return null
  }

  private fun childText(parent: Element, tagName: String): String? =
    childElements(parent).firstOrNull { it.tagName == tagName }?.textContent?.trim()

  private fun childElements(parent: Element): List<Element> {
    val children = mutableListOf<Element>()
    val nodeList = parent.childNodes
    for (index in 0 until nodeList.length) {
      val child = nodeList.item(index)
      if (child is Element) {
        children.add(child)
      }
    }
    return children
  }

  private data class Coordinates(
    val groupId: String,
    val artifactId: String,
    val version: String
  ) {
    val pomRelativePath: String
      get() = "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"

    fun artifactRelativePath(extension: String, classifier: String?): String {
      val classifierSuffix = classifier?.takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
      return "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version$classifierSuffix.$extension"
    }
  }
}
