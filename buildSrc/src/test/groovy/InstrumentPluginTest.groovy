import net.bytebuddy.utility.OpenedClassReader
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import spock.lang.Specification
import spock.lang.TempDir

class InstrumentPluginTest extends Specification {

  def buildGradle = '''
    plugins {
      id 'java'
      id 'instrument'
    }
    
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    repositories {
      mavenCentral()
    }

    dependencies {
      compileOnly group: 'net.bytebuddy', name: 'byte-buddy', version: '1.17.7' // just to build TestPlugin
    }

    apply plugin: 'instrument'

    instrument.plugins = [
      'TestPlugin'
    ]
  '''

  def testPlugin = '''
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
  '''

  def exampleCode = '''
    package example; public class ExampleCode {}
  '''

  @TempDir
  File buildDir

  def 'test instrument plugin'() {
    setup:
    def tree = new FileTreeBuilder(buildDir)
    tree.'build.gradle'(buildGradle)
    tree.src {
      main {
        java {
          'TestPlugin.java'(testPlugin)
          example {
            'ExampleCode.java'(exampleCode)
          }
        }
      }
    }

    when:
    BuildResult result = GradleRunner.create()
      .withTestKitDir(new File(buildDir, '.gradle-test-kit'))  // workaround in case the global test-kit cache becomes corrupted
      .withDebug(true)                                         // avoids starting daemon which can leave undeleted files post-cleanup
      .withProjectDir(buildDir)
      .withArguments('build', '--stacktrace')
      .withPluginClasspath()
      .forwardOutput()
      .build()

    File classFile = new File(buildDir, 'build/classes/java/main/example/ExampleCode.class')

    then:
    assert classFile.isFile()

    boolean foundInsertedField = false
    new ClassReader(new FileInputStream(classFile)).accept(new ClassVisitor(OpenedClassReader.ASM_API) {
      @Override
      FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
        if ('__TEST__FIELD__' == fieldName) {
          foundInsertedField = true
        }
        return null
      }
    }, OpenedClassReader.ASM_API)

    assert foundInsertedField
  }
}
