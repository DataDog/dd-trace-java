package datadog.gradle.plugin.instrument

import net.bytebuddy.utility.OpenedClassReader
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import java.io.File
import java.io.FileInputStream

class InstrumentPluginTest {

  private val buildGradle = """
    plugins {
      id 'java'
      id 'datadog.instrument'
    }
    
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    repositories {
      mavenCentral()
    }

    dependencies {
      compileOnly group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.1' // just to build TestPlugin
    }

    configurations {
      instrumentPluginClasspath {
        canBeResolved = true
      }
    }

    instrument.plugins = [
      'TestPlugin'
    ]
  """.trimIndent()

  private val testPlugin = """
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
        return "ExampleCode".equals(target.getSimpleName());
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

    val srcMainJava = File(buildDir, "src/main/java").apply { mkdirs() }
    File(srcMainJava, "TestPlugin.java").writeText(testPlugin)

    val examplePackageDir = File(srcMainJava, "example").apply { mkdirs() }
    File(examplePackageDir, "ExampleCode.java").writeText(exampleCode)

    // Run Gradle build with TestKit
    val result = GradleRunner.create().withTestKitDir(File(buildDir, ".gradle-test-kit")) // workaround in case the global test-kit cache becomes corrupted
      .withDebug(true) // avoids starting daemon which can leave undeleted files post-cleanup
      .withProjectDir(buildDir)
      .withArguments("build", "--stacktrace")
      .withPluginClasspath()
      .forwardOutput()
      .build()

    val classFile = File(buildDir, "build/classes/java/main/example/ExampleCode.class")
    assertTrue(classFile.isFile)

    var foundInsertedField = false
    FileInputStream(classFile).use { input ->
      val classReader = ClassReader(input)
      classReader.accept(
        object : ClassVisitor(OpenedClassReader.ASM_API) {
          override fun visitField(access: Int, fieldName: String?, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
            if ("__TEST__FIELD__" == fieldName) {
              foundInsertedField = true
            }
            return null
          }
        },
        OpenedClassReader.ASM_API
      )
    }

    assertTrue(foundInsertedField)
  }
}
