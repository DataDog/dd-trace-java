package datadog.trace.civisibility.codeowners

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import spock.lang.Specification

import java.nio.file.Files

class CodeownersProviderTest extends Specification {

  private static final String REPO_ROOT = "/repo/root"

  def "test codeowners loading: #path"() {
    setup:
    def fileSystem = Jimfs.newFileSystem(Configuration.unix())
    def codeownersPath = fileSystem.getPath(path)

    CodeownersProviderTest.getClassLoader().getResourceAsStream("ci/codeowners/CODEOWNERS_sample").withCloseable {
      Files.createDirectories(codeownersPath.getParent())
      Files.copy(it, codeownersPath)
    }

    when:
    def codeownersProvider = new CodeownersProvider(fileSystem)
    def codeowners = codeownersProvider.build(REPO_ROOT)
    def owners = codeowners.getOwners("folder/MyClass.java")

    then:
    owners == ["@global-owner1", "@global-owner2"]

    where:
    path << [
      REPO_ROOT + "/CODEOWNERS",
      REPO_ROOT + "/.github/CODEOWNERS",
      REPO_ROOT + "/.gitlab/CODEOWNERS",
      REPO_ROOT + "/docs/CODEOWNERS"
    ]
  }
}
