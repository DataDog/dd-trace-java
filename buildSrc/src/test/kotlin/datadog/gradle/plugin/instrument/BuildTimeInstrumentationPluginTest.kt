package datadog.gradle.plugin.instrument

import net.bytebuddy.utility.OpenedClassReader
import org.gradle.testkit.runner.GradleRunner
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
  """.trimIndent()

  private val exampleCode = """
    package example;
    public class ExampleCode {}
  """.trimIndent()

  @TempDir
  lateinit var buildDir: File

  @Test
  fun `test instrument plugin`() {
    val buildFile = File(buildDir, "build.gradle")
    buildFile.writeText(buildGradle)

    val srcMainJava = testPlugin("src/main/java", "ExampleCode")

    val examplePackageDir = File(srcMainJava, "example").apply { mkdirs() }
    File(examplePackageDir, "ExampleCode.java").writeText(exampleCode)

    // Run Gradle build with TestKit
    GradleRunner.create().withTestKitDir(File(buildDir, ".gradle-test-kit")) // workaround in case the global test-kit cache becomes corrupted
      .withDebug(true) // avoids starting daemon which can leave undeleted files post-cleanup
      .withProjectDir(buildDir)
      .withArguments("build", "--stacktrace")
      .withPluginClasspath()
      .forwardOutput()
      .build()

    assertInstrumented(File(buildDir, "build/classes/java/main/example/ExampleCode.class"))
  }

  @Test
  fun `test instrument plugin processes includeClassDirectories`() {
    val buildFile = File(buildDir, "build.gradle")
    buildFile.writeText("""
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
    """.trimIndent())

    testPlugin("src/main/java", "ExternalCode")

    // Pre-compile ExternalCode using ASM and place it in the external-classes directory
    val externalClassesDir = File(buildDir, "external-classes").apply { mkdirs() }
    precompiledClass("ExternalCode", externalClassesDir)

    GradleRunner.create()
      .withTestKitDir(File(buildDir, ".gradle-test-kit"))
      .withDebug(true)
      .withProjectDir(buildDir)
      .withArguments("build", "--stacktrace")
      .withPluginClasspath()
      .forwardOutput()
      .build()

    // ExternalCode.class should have been copied from external-classes, instrumented, and placed in the output
    assertInstrumented(File(buildDir, "build/classes/java/main/ExternalCode.class"))
  }

  @Test
  fun `test rerun-tasks does not lose includeClassDirectories classes`() {
    val buildFile = File(buildDir, "build.gradle")
    buildFile.writeText("""
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
    """.trimIndent())

    val srcMainJava = testPlugin("src/main/java", "ExampleCode", "ExternalCode")
    val examplePackageDir = File(srcMainJava, "example").apply { mkdirs() }
    File(examplePackageDir, "ExampleCode.java").writeText("package example; public class ExampleCode {}")

    val externalClassesDir = File(buildDir, "external-classes").apply { mkdirs() }
    precompiledClass("ExternalCode", externalClassesDir)

    val runner = GradleRunner.create()
      .withTestKitDir(File(buildDir, ".gradle-test-kit"))
      .withDebug(true)
      .withProjectDir(buildDir)
      .withPluginClasspath()
      .forwardOutput()

    // First build
    runner.withArguments("build", "--stacktrace").build()

    // Second build with --rerun-tasks: compileJava wipes classesDirectory, so without
    // the fix InstrumentAction would only sync freshly-compiled classes and lose ExternalCode.class
    runner.withArguments("build", "--rerun-tasks", "--stacktrace").build()

    assertInstrumented(File(buildDir, "build/classes/java/main/example/ExampleCode.class"))
    assertInstrumented(File(buildDir, "build/classes/java/main/ExternalCode.class"))
  }

  private fun testPlugin(srcDir: String, vararg classNames: String): File {
    val dir = File(buildDir, srcDir).apply { mkdirs() }
    val conditions = classNames.joinToString(" || ") { "\"$it\".equals(name)" }
    File(dir, "TestPlugin.java").writeText("""
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
    """.trimIndent())
    return dir
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
