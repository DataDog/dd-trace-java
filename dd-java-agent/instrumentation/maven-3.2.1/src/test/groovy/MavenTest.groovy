import datadog.trace.agent.test.base.TestFrameworkTest
import org.apache.maven.cli.MavenCli
import org.codehaus.plexus.classworlds.ClassWorld
import org.codehaus.plexus.util.FileUtils
import spock.lang.TempDir

import java.nio.file.Path
import java.nio.file.Paths

class MavenTest extends TestFrameworkTest {

  @TempDir
  Path projectFolder

  def "test_successful_maven_build_generates_spans"() {
    given:
    givenMavenProjectFiles("test_successful_maven_build_generates_spans") // FIXME get test name dynamically

    when:
    String[] args = ["verify"]
    String workingDirectory = projectFolder.toString()

    ClassWorld classWorld = null
    MavenCli mavenCli = new MavenCli(classWorld)

    def exitCode = mavenCli.doMain(args, workingDirectory, null, null)

    then:
    exitCode == 0

    assertTraces(1) {
      trace(1, true) {
        // FIXME add assert for session span
      }
    }
  }

  private void givenMavenProjectFiles(String projectFilesSources) {
    def projectResourcesUri = this.getClass().getClassLoader().getResource(projectFilesSources).toURI()
    def projectResourcesPath = Paths.get(projectResourcesUri)
    FileUtils.copyDirectory(projectResourcesPath.toFile(), projectFolder.toFile())
  }

  @Override
  String expectedOperationPrefix() {
    return "maven"
  }

  @Override
  String expectedTestFramework() {
    return "junit4"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return "4.3.12"
  }

  @Override
  String component() {
    return "maven"
  }
}
