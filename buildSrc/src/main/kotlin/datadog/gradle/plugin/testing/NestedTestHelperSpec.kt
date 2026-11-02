package datadog.gradle.plugin.testing

import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileTreeElement
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.InputStream
import java.net.URLClassLoader

/**
 * Removes nested helpers from independent forked-test discovery, without loading test classes.
 * Unknown or unreadable hierarchies remain candidates. Top-level classes are never excluded.
 * Recognizes JUnit and Spock metadata, including composed annotations and inherited tests;
 * tasks with other JUnit Platform engines retain every candidate.
 */
class NestedTestHelperSpec(private val classpath: Provider<Set<FileSystemLocation>>) : Spec<FileTreeElement> {
  override fun isSatisfiedBy(element: FileTreeElement): Boolean {
    if (element.isDirectory || !element.name.endsWith(".class") || '$' !in element.name) return false
    return try {
      val candidate = element.file.inputStream().use { readMetadata(it) }
      // A dollar in a top-level class name is not evidence of nesting.
      candidate.nested && !candidate.hasDirectTestMarker() && isHelper(candidate)
    } catch (_: Exception) {
      // Missing, malformed or unsupported bytecode remains a candidate.
      false
    }
  }

  private fun isHelper(candidate: Metadata): Boolean {
    val urls = classpath.get().map { it.asFile.toURI().toURL() }.toTypedArray()
    return URLClassLoader(urls, null).use { loader ->
      !hasUnknownTestEngine(loader) && !Hierarchy(loader, candidate).mayBeTest(candidate.name, includeChildren = true)
    }
  }

  /** An unfamiliar engine may discover tests using conventions other than annotations/inheritance. */
  private fun hasUnknownTestEngine(loader: ClassLoader): Boolean =
    loader.getResources(TEST_ENGINE_SERVICE).asSequence().any { service ->
      service.openStream().bufferedReader().useLines { lines ->
        lines.map { it.substringBefore('#').trim() }.any { it.isNotEmpty() && it !in KNOWN_TEST_ENGINES }
      }
    }

  /** Walks annotations, supertypes and nested classes, reading bytecode from the test runtime classpath. */
  private class Hierarchy(private val loader: ClassLoader, candidate: Metadata) {
    private val classes = mutableMapOf(candidate.name to candidate)
    private val visiting = mutableSetOf<String>()

    fun mayBeTest(name: String, includeChildren: Boolean = false): Boolean {
      if (name == "java/lang/Object" || name == "groovy/lang/GroovyObject") return false
      if (isTestMarker(name)) return true
      // Java's annotation metadata cannot define a test. Avoid its recursive meta-annotations.
      if (name.startsWith("java/lang/annotation/") || name == "groovy/transform/Generated") return false
      // Cycles and unresolvable types are treated as possible tests.
      if (!visiting.add(name)) return true
      val type = read(name) ?: return true
      val result = type.annotations.any { mayBeTest(it) } ||
        type.parents.any { mayBeTest(it) } ||
        (includeChildren && type.children.any { mayBeTest(it, includeChildren = true) })
      visiting.remove(name)
      return result
    }

    // The loader has no parent, so JDK classes still resolve through the bootstrap loader.
    private fun read(name: String): Metadata? = classes[name]
      ?: loader.getResourceAsStream("$name.class")?.use { readMetadata(it) }?.also { classes[name] = it }
  }

  private class Metadata(val name: String) {
    var nested = false
    val parents = mutableListOf<String>()
    val annotations = mutableSetOf<String>()
    val children = mutableSetOf<String>()

    fun hasDirectTestMarker(): Boolean = annotations.any { isTestMarker(it) } || parents.any { isTestMarker(it) }
  }

  private companion object {
    const val TEST_ENGINE_SERVICE = "META-INF/services/org.junit.platform.engine.TestEngine"

    val KNOWN_TEST_ENGINES = setOf(
      "org.junit.jupiter.engine.JupiterTestEngine",
      "org.junit.vintage.engine.VintageTestEngine",
      "org.junit.platform.suite.engine.SuiteTestEngine",
      "org.spockframework.runtime.SpockEngine",
    )

    fun isTestMarker(name: String): Boolean = name == "spock/lang/Specification" ||
      name == "junit/framework/TestCase" ||
      name == "junit/framework/Test" ||
      name.startsWith("org/junit/") ||
      name.startsWith("org/testng/") ||
      name.startsWith("org/spockframework/")

    fun readMetadata(input: InputStream): Metadata {
      val reader = ClassReader(input)
      require(reader.readInt(0) == 0xCAFEBABE.toInt())
      val type = Metadata(reader.className)
      reader.superName?.let { type.parents.add(it) }
      type.parents.addAll(reader.interfaces)
      reader.accept(MetadataVisitor(type), ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
      return type
    }
  }

  private class MetadataVisitor(private val type: Metadata) : ClassVisitor(Opcodes.ASM9) {
    private val methodVisitor = object : MethodVisitor(Opcodes.ASM9) {
      override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        type.annotations.add(internalName(descriptor))
        return null
      }
    }

    override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
      type.nested = true
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
      if (name == type.name) type.nested = true
      if (outerName == type.name) type.children.add(name)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
      type.annotations.add(internalName(descriptor))
      return null
    }

    override fun visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      exceptions: Array<out String>?,
    ): MethodVisitor {
      // JUnit 3 suite factories need neither annotations nor a TestCase superclass.
      if (name == "suite" && descriptor == "()Ljunit/framework/Test;" && access and Opcodes.ACC_STATIC != 0) {
        type.annotations.add("junit/framework/Test")
      }
      return methodVisitor
    }

    private fun internalName(descriptor: String) = descriptor.substring(1, descriptor.length - 1)
  }
}
