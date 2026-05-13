package datadog.gradle.plugin.instrument

import datadog.gradle.plugin.GradleFixture
import net.bytebuddy.utility.OpenedClassReader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileInputStream

class BuildTimeInstrumentationPluginTest {

  private val buildGradle = """
    plugins {
      id 'java'
      id 'dd-trace-java.build-time-instrumentation'
    }

    java {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }

    repositories {
      mavenCentral()
    }

    dependencies {
      compileOnly group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.8' // just to build TestPlugin
    }

    buildTimeInstrumentation.plugins = [
      'TestPlugin'
    ]
  """

  private val exampleCode = """
    package example;
    public class ExampleCode {}
  """.trimIndent()

  @TempDir
  lateinit var buildDir: File

  @Test
  fun `test instrument plugin`() {
    val fixture = GradleFixture(buildDir)
    fixture.rootProject(buildGradle)

    writeTestPlugin(fixture, "ExampleCode")
    fixture.appendTo("src/main/java/example/ExampleCode.java", exampleCode)

    fixture.run("build", "--stacktrace", forwardOutput = true)

    assertInstrumented(File(buildDir, "build/classes/java/main/example/ExampleCode.class"))
  }

  @Test
  fun `test instrument plugin processes includeClassDirectories`() {
    val fixture = GradleFixture(buildDir)
    fixture.rootProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.build-time-instrumentation'
      }

      java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }

      repositories {
        mavenCentral()
      }

      dependencies {
        compileOnly group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.8'
      }

      buildTimeInstrumentation {
        plugins = ['TestPlugin']
        includeClassDirectories.from(file('external-classes'))
      }
      """
    )

    writeTestPlugin(fixture, "ExternalCode")

    // Pre-compile ExternalCode using ASM and place it in the external-classes directory
    val externalClassesDir = fixture.file("external-classes").apply { mkdirs() }
    precompiledClass("ExternalCode", externalClassesDir)

    fixture.run("build", "--stacktrace", forwardOutput = true)

    // ExternalCode.class should have been copied from external-classes, instrumented, and placed in the output
    assertInstrumented(File(buildDir, "build/classes/java/main/ExternalCode.class"))
  }

  @Test
  fun `test rerun-tasks does not lose includeClassDirectories classes`() {
    val fixture = GradleFixture(buildDir)
    fixture.rootProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.build-time-instrumentation'
      }

      java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }

      repositories {
        mavenCentral()
      }

      dependencies {
        compileOnly group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.8'
      }

      buildTimeInstrumentation {
        plugins = ['TestPlugin']
        includeClassDirectories.from(file('external-classes'))
      }
      """
    )

    writeTestPlugin(fixture, "ExampleCode", "ExternalCode")
    fixture.appendTo("src/main/java/example/ExampleCode.java", "package example; public class ExampleCode {}")

    val externalClassesDir = fixture.file("external-classes").apply { mkdirs() }
    precompiledClass("ExternalCode", externalClassesDir)

    // First build
    fixture.run("build", "--stacktrace", forwardOutput = true)

    // Second build with --rerun-tasks: compileJava wipes classesDirectory, so without
    // the fix InstrumentAction would only sync freshly-compiled classes and lose ExternalCode.class
    fixture.run("build", "--rerun-tasks", "--stacktrace", forwardOutput = true)

    assertInstrumented(File(buildDir, "build/classes/java/main/example/ExampleCode.class"))
    assertInstrumented(File(buildDir, "build/classes/java/main/ExternalCode.class"))
  }

  private fun writeTestPlugin(fixture: GradleFixture, vararg classNames: String) {
    val conditions = classNames.joinToString(" || ") { "\"$it\".equals(name)" }
    fixture.appendTo(
      "src/main/java/TestPlugin.java",
      """
      import java.io.File;
      import java.io.IOException;
      import net.bytebuddy.build.Plugin;
      import net.bytebuddy.description.type.TypeDescription;
      import net.bytebuddy.dynamic.ClassFileLocator;
      import net.bytebuddy.dynamic.DynamicType;

      public class TestPlugin implements Plugin {
        private final File targetDir;

        public TestPlugin(File targetDir) {
          this.targetDir = targetDir;
        }

        @Override
        public boolean matches(TypeDescription target) {
          String name = target.getSimpleName();
          return $conditions;
        }

        @Override
        public DynamicType.Builder<?> apply(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassFileLocator classFileLocator) {
          return builder.defineField("__TEST__FIELD__", Void.class);
        }

        @Override
        public void close() throws IOException {
          // no-op
        }
      }
      """.trimIndent()
    )
  }

  private fun precompiledClass(className: String, targetDir: File) {
    val classWriter = ClassWriter(0)
    classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
    classWriter.visitEnd()
    File(targetDir, "$className.class").writeBytes(classWriter.toByteArray())
  }

  private fun assertInstrumented(classFile: File) {
    assertTrue(classFile.isFile, "${classFile.name} should be present in the output directory")
    var foundInsertedField = false
    FileInputStream(classFile).use { input ->
      val classReader = ClassReader(input)
      classReader.accept(
        object : ClassVisitor(OpenedClassReader.ASM_API) {
          override fun visitField(access: Int, fieldName: String?, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
            if ("__TEST__FIELD__" == fieldName) foundInsertedField = true
            return null
          }
        },
        OpenedClassReader.ASM_API
      )
    }
    assertTrue(foundInsertedField, "${classFile.name} should have been instrumented with __TEST__FIELD__")
  }
}
