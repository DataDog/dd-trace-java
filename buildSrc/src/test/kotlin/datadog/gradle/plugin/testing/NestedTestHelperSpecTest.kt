package datadog.gradle.plugin.testing

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.file.FileTreeElement
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.V1_8
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.api.tasks.testing.Test as GradleTest

class NestedTestHelperSpecTest {
  @TempDir lateinit var directory: File

  private val project by lazy { ProjectBuilder.builder().withProjectDir(directory).build() }
  private val classpath by lazy { project.objects.fileCollection().from(directory) }
  private val spec by lazy { NestedTestHelperSpec(classpath.elements) }

  @Test
  fun `Java and Groovy helpers are excluded but top level classes are retained`() {
    val java = writeClass("ExampleForkedTest\$Runnable", interfaces = arrayOf("java/lang/Runnable"))
    val groovy = writeClass(
      "ExampleForkedTest\$Server",
      interfaces = arrayOf("groovy/lang/GroovyObject"),
      annotation = "groovy/transform/Generated",
    )
    val queue = writeClass("ExampleForkedTest\$Queue", superclass = "java/util/concurrent/LinkedBlockingQueue")
    assertExcluded(java, groovy, queue)
    assertRetained(writeClass("ExampleForkedTest"), writeClass("Dollar\$ForkedTest", nested = false))
  }

  @Test
  fun `direct composed and inherited JUnit tests remain eligible`() {
    writeClass("ComposedTest", classAnnotation = "org/junit/platform/commons/annotation/Testable")
    writeClass("Base", annotation = "ComposedTest")
    writeClass("TestContract", annotation = "org/junit/jupiter/api/Test")
    assertRetained(
      writeClass("ExampleForkedTest\$Direct", annotation = "org/junit/jupiter/api/Test"),
      writeClass("ExampleForkedTest\$Inherited", superclass = "Base"),
      writeClass("ExampleForkedTest\$DefaultMethod", interfaces = arrayOf("TestContract")),
      writeClass("ExampleForkedTest\$Factory", annotation = "org/junit/jupiter/api/TestFactory"),
      writeClass("ExampleForkedTest\$Nested", annotation = "org/junit/jupiter/api/Nested"),
      writeClass("ExampleForkedTest\$Runner", classAnnotation = "org/junit/runner/RunWith"),
      writeClass("ExampleForkedTest\$Disabled", annotation = "org/junit/jupiter/api/Disabled"),
      writeClass("ExampleForkedTest\$Vintage", superclass = "junit/framework/TestCase"),
      writeClass("ExampleForkedTest\$SuiteFactory", suiteFactory = true),
    )
  }

  @Test
  fun `Spock specifications and ignored inherited specifications remain eligible`() {
    writeClass("BaseSpec", superclass = "spock/lang/Specification")
    assertRetained(
      writeClass("ExampleForkedTest\$Spec", superclass = "spock/lang/Specification"),
      writeClass("ExampleForkedTest\$InheritedSpec", superclass = "BaseSpec"),
      writeClass("ExampleForkedTest\$IgnoredSpec", superclass = "BaseSpec", annotation = "spock/lang/Ignore"),
    )
  }

  @Test
  fun `containers with nested tests remain eligible`() {
    writeClass("ExampleForkedTest\$Container\$Test", annotation = "org/junit/jupiter/api/Test")
    assertRetained(writeClass("ExampleForkedTest\$Container", child = "ExampleForkedTest\$Container\$Test"))
  }

  @Test
  fun `hierarchies and composed annotations are read from runtime jars without loading classes`() {
    val helper = writeClass("LibraryHelper")
    val test = writeClass("LibraryTest", annotation = "org/junit/jupiter/api/Test")
    val jar = File(directory, "library.jar")
    JarOutputStream(jar.outputStream()).use { out ->
      listOf(helper, test).forEach {
        out.putNextEntry(JarEntry(it.name))
        out.write(it.readBytes())
        out.closeEntry()
        it.delete()
      }
    }
    classpath.from(jar)
    assertExcluded(writeClass("ExampleForkedTest\$Helper", superclass = "LibraryHelper"))
    assertRetained(writeClass("ExampleForkedTest\$Test", superclass = "LibraryTest"))
  }

  @Test
  fun `unresolved malformed cyclic and unsupported metadata remains eligible`() {
    writeClass("CycleA", superclass = "CycleB")
    writeClass("CycleB", superclass = "CycleA")
    val malformed = writeClass("ExampleForkedTest\$Malformed").apply { writeBytes(byteArrayOf()) }
    val unsupported = writeClass("ExampleForkedTest\$Future").apply {
      val bytes = readBytes()
      bytes[6] = 0x7f
      bytes[7] = 0x7f
      writeBytes(bytes)
    }
    assertRetained(
      writeClass("ExampleForkedTest\$UnknownBase", superclass = "Missing"),
      writeClass("ExampleForkedTest\$UnknownAnnotation", annotation = "Missing"),
      writeClass("ExampleForkedTest\$Cycle", superclass = "CycleA"),
      malformed,
      unsupported,
    )
    assertThat(spec.isSatisfiedBy(element(File(directory, "classes.class").apply { mkdir() }))).isFalse()
    val missing = writeClass("ExampleForkedTest\$Missing")
    val missingElement = element(missing)
    missing.delete()
    assertThat(spec.isSatisfiedBy(missingElement)).isFalse()
  }

  @Test
  fun `unknown test engines retain otherwise empty helpers`() {
    File(directory, "META-INF/services/org.junit.platform.engine.TestEngine").apply {
      parentFile.mkdirs()
      writeText("custom.ConventionTestEngine # may discover tests without annotations\n")
    }
    assertRetained(writeClass("ExampleForkedTest\$Helper"))
  }

  @Test
  fun `recompilation changes classification without stale metadata`() {
    val file = writeClass("ExampleForkedTest\$Changing")
    val element = element(file)
    assertThat(spec.isSatisfiedBy(element)).isTrue()
    writeClass("ExampleForkedTest\$Changing", annotation = "org/junit/jupiter/api/Test")
    assertThat(spec.isSatisfiedBy(element)).isFalse()
    writeClass("ExampleForkedTest\$Changing")
    assertThat(spec.isSatisfiedBy(element)).isTrue()
  }

  @Test
  fun `candidate filtering preserves runtime helpers selection and isolation`() {
    val helper = writeClass("ExampleForkedTest\$Helper")
    val tests = listOf(
      writeClass("ExampleForkedTest"),
      writeClass("ExampleForkedTest\$Nested", annotation = "org/junit/jupiter/api/Test"),
      writeClass("ExampleForkedTest\$Spec", superclass = "spock/lang/Specification"),
    )
    val task = project.tasks.create("forkedTest", GradleTest::class.java)
    task.testClassesDirs = project.files(directory)
    task.classpath = classpath
    task.exclude(spec)
    task.setIncludes(listOf("**/*ForkedTest*"))
    task.forkEvery = 1
    task.filter.includeTestsMatching("ExampleForkedTest*")

    assertThat(task.candidateClassFiles.files).containsExactlyInAnyOrderElementsOf(tests)
    assertThat(task.classpath.asFileTree.files).contains(helper)
    assertThat(task.forkEvery).isEqualTo(1)
    assertThat(task.filter.includePatterns).containsExactly("ExampleForkedTest*")
  }

  private fun assertExcluded(vararg files: File) = files.forEach {
    assertThat(spec.isSatisfiedBy(element(it))).describedAs(it.name).isTrue()
  }

  private fun assertRetained(vararg files: File) = files.forEach {
    assertThat(spec.isSatisfiedBy(element(it))).describedAs(it.name).isFalse()
  }

  private fun writeClass(
    name: String,
    superclass: String = "java/lang/Object",
    interfaces: Array<String> = emptyArray(),
    annotation: String? = null,
    classAnnotation: String? = null,
    nested: Boolean = '$' in name,
    child: String? = null,
    suiteFactory: Boolean = false,
  ): File {
    val writer = ClassWriter(0)
    writer.visit(V1_8, ACC_PUBLIC, name, null, superclass, interfaces)
    if (classAnnotation != null) writer.visitAnnotation("L$classAnnotation;", true).visitEnd()
    if (nested) writer.visitInnerClass(name, name.substringBeforeLast('$'), name.substringAfterLast('$'), ACC_PUBLIC)
    if (child != null) writer.visitInnerClass(child, name, child.substringAfterLast('$'), ACC_PUBLIC)
    if (suiteFactory) writer.visitMethod(ACC_PUBLIC or ACC_STATIC, "suite", "()Ljunit/framework/Test;", null, null).visitEnd()
    // Annotation-bearing methods also cover inherited and composed test methods.
    if (annotation != null) {
      writer.visitMethod(ACC_PUBLIC, "test", "()V", null, null).apply {
        visitAnnotation("L$annotation;", true).visitEnd()
        visitEnd()
      }
    }
    writer.visitEnd()
    return File(directory, "$name.class").apply { writeBytes(writer.toByteArray()) }
  }

  private fun element(file: File): FileTreeElement {
    var result: FileTreeElement? = null
    project.fileTree(directory).visit { if (this.file == file) result = this }
    return requireNotNull(result)
  }
}
