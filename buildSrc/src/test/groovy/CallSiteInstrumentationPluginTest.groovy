import datadog.trace.plugin.csi.util.ErrorCode
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import spock.lang.Specification
import spock.lang.TempDir

class CallSiteInstrumentationPluginTest extends Specification {

  def buildGradle = '''
    plugins {
      id 'java'
      id 'call-site-instrumentation'
      id("com.diffplug.spotless") version "5.11.0"
    }

    csi {
      suffix = 'CallSiteTest'
      targetFolder = 'csi'
    }
    
    repositories {
      mavenCentral()
    }
    
    dependencies {
      implementation group: 'net.bytebuddy', name: 'byte-buddy', version: '1.12.12'
      implementation group: 'com.google.auto.service', name: 'auto-service-annotations', version: '1.0-rc7'
    }
  '''

  @TempDir
  File buildDir

  def 'test call site instrumentation plugin'() {
    setup:
    createGradleProject(buildDir, buildGradle, '''
      import datadog.trace.agent.tooling.csi.*;
      import net.bytebuddy.asm.Advice;
      
      @CallSite
      public class BeforeAdviceCallSiteTest {
        @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
        public static void beforeAppend(@Advice.This final StringBuilder self, @Advice.Argument(0) final String param) {
        }
      }
  ''')

    when:
    final result = buildGradleProject(buildDir)

    then:
    final generated = new File(buildDir, 'build/csi/BeforeAdviceCallSiteTestBeforeAppend.java')
    generated.exists()

    final output = result.output
    !output.contains('[⨉]')
    output.contains('BeforeAdviceCallSiteTest')
    output.contains('beforeAppend')
    output.contains('java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)') // pointcut
  }

  def 'test call site instrumentation plugin with error'() {
    setup:
    createGradleProject(buildDir, buildGradle, '''
      import datadog.trace.agent.tooling.csi.*;
      import net.bytebuddy.asm.Advice;
      
      @CallSite
      public class BeforeAdviceCallSiteTest {
        @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
        private void beforeAppend(@Advice.This final StringBuilder self, @Advice.Argument(0) final String param) {
        }
      }
  ''')

    when:
    buildGradleProject(buildDir)

    then:
    final error = thrown(UnexpectedBuildFailure)

    final generated = new File(buildDir, 'build/csi/BeforeAdviceCallSiteTest$BeforeAppend.java')
    !generated.exists()

    final output = error.message
    !output.contains('[✓]')
    output.contains(ErrorCode.ADVICE_METHOD_NOT_STATIC_AND_PUBLIC.name())
  }

  private static void createGradleProject(final File buildDir, final String gradleFile, final String advice) {
    final buildGradle = new File(buildDir, 'build.gradle')
    buildGradle.text = gradleFile

    final javaFolder = new File(buildDir, 'src/main/java')
    javaFolder.mkdirs()

    final advicePackage = parsePackage(advice)
    final adviceClassName = parseClassName(advice)
    final adviceFolder = new File(javaFolder, "${advicePackage.replaceAll('\\.', '/')}")
    adviceFolder.mkdirs()
    final adviceFile = new File(adviceFolder, "${adviceClassName}.java")
    adviceFile.text = advice

    final projectFolder = new File(System.getProperty('user.dir')).parentFile
    final csiSource = new File(projectFolder, 'dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/csi')
    final csiTarget = new File(javaFolder, 'datadog/trace/agent/tooling/csi')
    csiTarget.mkdirs()
    csiSource.listFiles().each { new File(csiTarget, it.name).text = it.text }
  }

  private static BuildResult buildGradleProject(final File buildDir) {
    return GradleRunner.create()
      .withTestKitDir(new File(buildDir, '.gradle-test-kit'))  // workaround in case the global test-kit cache becomes corrupted
      .withDebug(true)                                         // avoids starting daemon which can leave undeleted files post-cleanup
      .withProjectDir(buildDir)
      .withArguments('build', '--info', '--stacktrace')
      .withPluginClasspath()
      .forwardOutput()
      .build()
  }

  private static String parsePackage(final String advice) {
    final advicePackageMatcher = advice =~ /(?s).*package\s+([\w\.]+)\s*;/
    return advicePackageMatcher ? advicePackageMatcher[0][1] as String : ''
  }

  private static String parseClassName(final String advice) {
    return (advice =~ /(?s).*class\s+(\w+)\s+\{\.*/)[0][1]
  }
}
