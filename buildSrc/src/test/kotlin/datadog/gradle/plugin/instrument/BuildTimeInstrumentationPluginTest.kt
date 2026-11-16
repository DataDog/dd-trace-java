package datadog.gradle.plugin.instrument

import datadog.gradle.plugin.GradleFixture
import net.bytebuddy.utility.OpenedClassReader
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileInputStream

class BuildTimeInstrumentationPluginTest : GradleFixture() {

  private val buildGradle = """
    plugins {
      id("java")
      id("dd-trace-java.build-time-instrumentation")
    }

    java {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }

    repositories {
      mavenCentral()
    }

    dependencies {
      compileOnly("net.bytebuddy:byte-buddy:1.18.12") // just to build TestPlugin
    }

    buildTimeInstrumentation.plugins.set(listOf("TestPlugin"))
  """

  private val exampleCode = """
    package example;
    public class ExampleCode {}
  """

  @Test
  fun `test instrument plugin`() {
    writeRootProject(buildGradle)
    writeTestPlugin("ExampleCode")
    writeJavaSource("example.ExampleCode", exampleCode)

    run("build", "--stacktrace", forwardOutput = true)

    assertInstrumented(buildFile("classes/java/main/example/ExampleCode.class"))
  }

  @Test
  fun `test instrument plugin processes includeClassDirectories`() {
    writeRootProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.build-time-instrumentation")
      }

      java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }

      repositories {
        mavenCentral()
      }

      dependencies {
        compileOnly("net.bytebuddy:byte-buddy:1.18.12")
      }

      buildTimeInstrumentation {
        plugins.set(listOf("TestPlugin"))
        includeClassDirectories.from(file("external-classes"))
      }
      """
    )

    writeTestPlugin("ExternalCode")

    // Pre-compile ExternalCode using ASM and place it in the external-classes directory
    val externalClassesDir = dir("external-classes")
    precompiledClass("ExternalCode", externalClassesDir)

    run("build", "--stacktrace", forwardOutput = true)

    // ExternalCode.class should have been copied from external-classes, instrumented, and placed in the output
    assertInstrumented(buildFile("classes/java/main/ExternalCode.class"))
  }

  @Test
  fun `test rerun-tasks does not lose includeClassDirectories classes`() {
    writeRootProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.build-time-instrumentation")
      }

      java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }

      repositories {
        mavenCentral()
      }

      dependencies {
        compileOnly("net.bytebuddy:byte-buddy:1.18.12")
      }

      buildTimeInstrumentation {
        plugins.set(listOf("TestPlugin"))
        includeClassDirectories.from(file("external-classes"))
      }
      """
    )

    writeTestPlugin("ExampleCode", "ExternalCode")
    writeJavaSource("example.ExampleCode", "package example; public class ExampleCode {}")

    val externalClassesDir = dir("external-classes")
    precompiledClass("ExternalCode", externalClassesDir)

    // First build
    run("build", "--stacktrace", forwardOutput = true)

    // Second build with --rerun-tasks: compileJava wipes classesDirectory, so without
    // the fix InstrumentAction would only sync freshly-compiled classes and lose ExternalCode.class
    run("build", "--rerun-tasks", "--stacktrace", forwardOutput = true)

    assertInstrumented(buildFile("classes/java/main/example/ExampleCode.class"))
    assertInstrumented(buildFile("classes/java/main/ExternalCode.class"))
  }

  @Test
  fun `test a method body change on the classpath reruns instrumentation`() {
    // Instrumentation reads classpath classes (muzzle records the library methods a helper calls),
    // so a body-only change there must rerun it, though compile avoidance ignores such a change.
    writeRootProject(
      buildGradle +
        """
        dependencies {
          implementation(project(":lib"))
        }
        """
    )
    addSubproject(
      "lib",
      """
      plugins {
        id("java")
      }

      java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
      """
    )
    writeHelperRecordingPlugin()
    writeJavaSource("example.ExampleCode", exampleCode)
    val exampleClass = buildFile("classes/java/main/example/ExampleCode.class")

    // Compile with the helper's original body: instrumentation sees value() == 1.
    writeJavaSource("lib.Helper", helperCode("return 1;"), projectPath = "lib")
    run("build", "--stacktrace", forwardOutput = true)
    assertHasField(exampleClass, "__HELPER_1__")

    // Compile with only the helper's body changed: instrumentation must rerun and see 2.
    writeJavaSource("lib.Helper", helperCode("return 2;"), projectPath = "lib")
    val result = run("build", "--stacktrace", forwardOutput = true)
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")?.outcome)
    assertHasField(exampleClass, "__HELPER_2__")
  }

  /**
   * A ByteBuddy plugin that calls `lib.Helper.value()` through the instrumenting class loader and
   * records the result as a field on `ExampleCode`, showing which helper body instrumentation saw.
   */
  private fun writeHelperRecordingPlugin() {
    writeJavaSource(
      "TestPlugin",
      """
      import java.io.File;
      import java.io.IOException;
      import net.bytebuddy.build.Plugin;
      import net.bytebuddy.description.type.TypeDescription;
      import net.bytebuddy.dynamic.ClassFileLocator;
      import net.bytebuddy.dynamic.DynamicType;

      public class TestPlugin implements Plugin {
        public TestPlugin(File targetDir) {}

        @Override
        public boolean matches(TypeDescription target) {
          return "ExampleCode".equals(target.getSimpleName());
        }

        @Override
        public DynamicType.Builder<?> apply(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassFileLocator classFileLocator) {
          try {
            Object value = Class.forName("lib.Helper", true, getClass().getClassLoader())
              .getMethod("value")
              .invoke(null);
            return builder.defineField("__HELPER_" + value + "__", Void.class);
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public void close() throws IOException {}
      }
      """
    )
  }

  private fun helperCode(body: String) = """
    package lib;
    public class Helper {
      public static int value() { $body }
    }
  """

  private fun writeTestPlugin(vararg classNames: String) {
    val conditions = classNames.joinToString(" || ") { "\"$it\".equals(name)" }
    writeJavaSource(
      "TestPlugin",
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
    """
    )
  }

  private fun precompiledClass(className: String, targetDir: File) {
    val classWriter = ClassWriter(0)
    classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
    classWriter.visitEnd()
    File(targetDir, "$className.class").writeBytes(classWriter.toByteArray())
  }

  private fun assertInstrumented(classFile: File) = assertHasField(classFile, "__TEST__FIELD__")

  private fun assertHasField(classFile: File, expectedField: String) {
    assertTrue(classFile.isFile, "${classFile.name} should be present in the output directory")
    var foundInsertedField = false
    FileInputStream(classFile).use { input ->
      val classReader = ClassReader(input)
      classReader.accept(
        object : ClassVisitor(OpenedClassReader.ASM_API) {
          override fun visitField(access: Int, fieldName: String?, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
            if (expectedField == fieldName) foundInsertedField = true
            return null
          }
        },
        OpenedClassReader.ASM_API
      )
    }
    assertTrue(foundInsertedField, "${classFile.name} should have been instrumented with $expectedField")
  }
}
