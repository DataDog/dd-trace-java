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
      id 'com.diffplug.spotless' version '6.13.0'
    }
    
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    csi {
      suffix = 'CallSite'
      targetFolder = 'csi'
      rootFolder = file('$$ROOT_FOLDER$$')
    }
    
    repositories {
      mavenCentral()
    }
    
    dependencies {
      implementation group: 'net.bytebuddy', name: 'byte-buddy', version: '1.18.1'
      implementation group: 'com.google.auto.service', name: 'auto-service-annotations', version: '1.1.1'
    }
  '''

  @TempDir
  File buildDir

  def 'test call site instrumentation plugin'() {
    setup:
    createGradleProject(buildDir, buildGradle, '''
      import datadog.trace.agent.tooling.csi.*;
      
      @CallSite(spi = CallSites.class)
      public class BeforeAdviceCallSite {
        @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
        public static void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
        }
      }
  ''')

    when:
    final result = buildGradleProject(buildDir)

    then:
    final generated = resolve(buildDir, 'build', 'csi', 'BeforeAdviceCallSites.java')
    generated.exists()

    final output = result.output
    !output.contains('[⨉]')
    output.contains('[✓] @CallSite BeforeAdviceCallSite')
  }

  def 'test call site instrumentation plugin with error'() {
    setup:
    createGradleProject(buildDir, buildGradle, '''
      import datadog.trace.agent.tooling.csi.*;
      
      @CallSite(spi = CallSites.class)
      public class BeforeAdviceCallSite {
        @CallSite.Before("java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)")
        private void beforeAppend(@CallSite.This final StringBuilder self, @CallSite.Argument final String param) {
        }
      }
  ''')

    when:
    buildGradleProject(buildDir)

    then:
    final error = thrown(UnexpectedBuildFailure)

    final generated = resolve(buildDir, 'build', 'csi', 'BeforeAdviceCallSites.java')
    !generated.exists()

    final output = error.message
    !output.contains('[✓]')
    output.contains('ADVICE_METHOD_NOT_STATIC_AND_PUBLIC')
  }

  private static void createGradleProject(final File buildDir, final String gradleFile, final String advice) {
    final projectFolder = new File(System.getProperty('user.dir')).parentFile
    final callSiteJar = resolve(projectFolder, 'buildSrc', 'call-site-instrumentation-plugin')
    final gradleFileContent = gradleFile.replace('$$ROOT_FOLDER$$', projectFolder.toString().replace("\\","\\\\"))

    final buildGradle = resolve(buildDir, 'build.gradle')
    buildGradle.text = gradleFileContent

    final javaFolder = resolve(buildDir, 'src', 'main', 'java')
    javaFolder.mkdirs()

    final advicePackage = parsePackage(advice)
    final adviceClassName = parseClassName(advice)
    final adviceFolder = resolve(javaFolder, advicePackage.split('\\.'))
    adviceFolder.mkdirs()
    final adviceFile = resolve(adviceFolder, "${adviceClassName}.java")
    adviceFile.text = advice

    final csiSource = resolve(projectFolder, 'dd-java-agent', 'agent-tooling', 'src', 'main', 'java', 'datadog', 'trace', 'agent', 'tooling', 'csi')
    final csiTarget = resolve(javaFolder, 'datadog', 'trace', 'agent', 'tooling', 'csi')
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

  private static File resolve(final File file, final String...path) {
    final result = path.inject(file.toPath()) {parent, folder -> parent.resolve(folder)}
    return result.toFile()
  }
}
