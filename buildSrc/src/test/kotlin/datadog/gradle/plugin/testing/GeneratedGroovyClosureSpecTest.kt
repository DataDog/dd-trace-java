package datadog.gradle.plugin.testing

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.file.FileTreeElement
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.V1_8
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.gradle.api.tasks.testing.Test as GradleTest

class GeneratedGroovyClosureSpecTest {
  @TempDir lateinit var directory: File

  private val spec = GeneratedGroovyClosureSpec()
  private val closure = "groovy/lang/Closure"
  private val marker = "org/codehaus/groovy/runtime/GeneratedClosure"

  @Test
  fun `only direct generated closure headers are excluded`() {
    val generated = writeClass("ExampleForkedTest\$_closure1", closure, marker)
    val retained = listOf(
      writeClass("ManualClosure", closure),
      writeClass("MarkerOnly", "java/lang/Object", marker),
      writeClass("ExampleForkedTest", "spock/lang/Specification"),
      writeClass("ExampleForkedTestV1", "ExampleForkedTest"),
      writeClass("ExampleForkedTest\$NestedSpec", "spock/lang/Specification"),
      writeClass("ExampleForkedTest\$Helper"),
      writeClass("IndirectClosure", "ManualClosure", marker),
    )

    assertThat(spec.isSatisfiedBy(element(generated))).isTrue()
    retained.forEach { file ->
      assertThat(spec.isSatisfiedBy(element(file))).describedAs(file.name).isFalse()
    }
  }

  @Test
  fun `directories resources unreadable and malformed bytecode remain eligible`() {
    val resource = File(directory, "resource.txt").apply { writeText("resource") }
    val classDirectory = File(directory, "classes.class").apply { mkdir() }
    val empty = File(directory, "Empty.class").apply { writeBytes(byteArrayOf()) }
    val truncated = File(directory, "Truncated.class").apply {
      writeBytes(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()))
    }
    val invalidMagic = writeClass("InvalidMagic", closure, marker).apply {
      val bytes = readBytes()
      // Corrupt the first byte of the class-file magic number (0xCAFEBABE).
      bytes[0] = 0
      writeBytes(bytes)
    }
    val unsupported = writeClass("Unsupported", closure, marker).apply {
      val bytes = readBytes()
      // Bytes 6–7 hold the major version; 0x7F7F is unsupported by ASM.
      bytes[6] = 0x7f
      bytes[7] = 0x7f
      writeBytes(bytes)
    }
    val missing = writeClass("Missing", closure, marker)
    val missingElement = element(missing)
    missing.delete()

    listOf(resource, classDirectory, empty, truncated, invalidMagic, unsupported).forEach { file ->
      assertThat(spec.isSatisfiedBy(element(file))).describedAs(file.name).isFalse()
    }
    assertThat(spec.isSatisfiedBy(missingElement)).isFalse()
  }

  @Test
  fun `recompilation at the same path is reclassified`() {
    val file = writeClass("ChangingForkedTest", closure, marker)
    val element = element(file)
    assertThat(spec.isSatisfiedBy(element)).isTrue()
    writeClass("ChangingForkedTest", "spock/lang/Specification")
    assertThat(spec.isSatisfiedBy(element)).isFalse()
    writeClass("ChangingForkedTest", closure, marker)
    assertThat(spec.isSatisfiedBy(element)).isTrue()
  }

  @Test
  fun `candidate filtering preserves test selection isolation and runtime files`() {
    val generated = writeClass("ExampleForkedTest\$_closure1", closure, marker)
    val retained = listOf(
      writeClass("ExampleForkedTest", "spock/lang/Specification"),
      writeClass("ExampleForkedTestV1", "ExampleForkedTest"),
      writeClass("ExampleForkedTest\$NestedSpec", "spock/lang/Specification"),
      writeClass("ExampleForkedTest\$Helper"),
      writeClass("ManualForkedTestClosure", closure),
    )
    writeClass("OrdinaryTest", "spock/lang/Specification")
    val project = ProjectBuilder.builder().withProjectDir(directory).build()
    val task = project.tasks.create("forkedTest", GradleTest::class.java)
    task.testClassesDirs = project.files(directory)
    task.classpath = project.files(directory)
    task.setExcludes(emptyList())
    task.exclude(spec)
    task.setIncludes(listOf("**/*ForkedTest*"))
    task.forkEvery = 1
    task.filter.includeTestsMatching("ExampleForkedTestV1.*")

    assertThat(task.candidateClassFiles.files).containsExactlyInAnyOrderElementsOf(retained)
    assertThat(task.includes).containsExactly("**/*ForkedTest*")
    assertThat(task.forkEvery).isEqualTo(1)
    assertThat(task.filter.includePatterns).containsExactly("ExampleForkedTestV1.*")
    assertThat(task.classpath.asFileTree.files).contains(generated)
  }

  @Test
  fun `predicate survives Java serialization without project state`() {
    val bytes = ByteArrayOutputStream()
    ObjectOutputStream(bytes).use { it.writeObject(spec) }
    val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
      it.readObject() as GeneratedGroovyClosureSpec
    }
    assertThat(restored.isSatisfiedBy(element(writeClass("Closure", closure, marker)))).isTrue()
    assertThat(restored.isSatisfiedBy(element(writeClass("Spec")))).isFalse()
  }

  private fun writeClass(
    name: String,
    superclass: String = "java/lang/Object",
    vararg interfaces: String,
  ): File {
    val writer = ClassWriter(0)
    writer.visit(V1_8, ACC_PUBLIC, name, null, superclass, interfaces)
    writer.visitEnd()
    return File(directory, "$name.class").apply { writeBytes(writer.toByteArray()) }
  }

  private fun element(file: File): FileTreeElement {
    val project = ProjectBuilder.builder().build()
    var result: FileTreeElement? = null
    project.fileTree(directory).visit { if (this.file == file) result = this }
    return requireNotNull(result) { "Missing fixture: $file" }
  }
}
