package datadog.trace.bootstrap.instrumentation.decorator.ci

class NoopCIInfoTest extends CIProviderInfoTest {

  def "Noop CI info is set properly"() {
    when:
    def ciInfo = new NoopCIInfo()

    then:
    ciInfo.ciProviderName == null
    ciInfo.ciPipelineId == null
    ciInfo.ciPipelineName == null
    ciInfo.ciPipelineNumber == null
    ciInfo.ciPipelineUrl == null
    ciInfo.ciJobUrl == null
    ciInfo.ciWorkspacePath == null
    ciInfo.gitRepositoryUrl == null
    ciInfo.gitCommit == null
    ciInfo.gitBranch == null
    ciInfo.gitTag == null

  }
}
