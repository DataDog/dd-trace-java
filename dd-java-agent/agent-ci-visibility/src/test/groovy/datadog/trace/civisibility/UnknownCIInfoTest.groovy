package datadog.trace.civisibility

import datadog.trace.api.civisibility.CIProviderInfo
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.nio.file.Paths

class UnknownCIInfoTest extends CITagsProviderImplTest {

  def workspaceForTests = Paths.get(getClass().getClassLoader().getResource(CITagsProviderImplTest.CI_WORKSPACE_PATH_FOR_TESTS).toURI())

  @Override
  CIProviderInfo instanceProvider() {
    return new UnknownCIInfo(CITagsProviderImplTest.GIT_FOLDER_FOR_TESTS, workspaceForTests)
  }

  @Override
  String getProviderName() {
    return UnknownCIInfo.UNKNOWN_PROVIDER_NAME
  }

  def "test info is set properly from local workspace"() {
    setup:
    def expectedTags = [
      (Tags.CI_WORKSPACE_PATH)         : "${workspaceForTests.toString()}",
      (Tags.GIT_REPOSITORY_URL)        : "https://some-host/some-user/some-repo.git",
      (Tags.GIT_BRANCH)                : "master",
      (Tags.GIT_COMMIT_SHA)            : "0797c248e019314fc1d91a483e859b32f4509953",
      (Tags.GIT_COMMIT_MESSAGE)        : "This is a commit message\n",
      (Tags.GIT_COMMIT_AUTHOR_NAME)    : "John Doe",
      (Tags.GIT_COMMIT_AUTHOR_EMAIL)   : "john@doe.com",
      (Tags.GIT_COMMIT_AUTHOR_DATE)    : "2021-02-12T13:47:48.000Z",
      (Tags.GIT_COMMIT_COMMITTER_NAME) : "Jane Doe",
      (Tags.GIT_COMMIT_COMMITTER_EMAIL): "jane@doe.com",
      (Tags.GIT_COMMIT_COMMITTER_DATE) : "2021-02-12T13:48:44.000Z"
    ]

    when:
    def ciProviderInfo = instanceProvider()
    def ciTagsProvider = ciTagsProvider()
    def ciTags = ciTagsProvider.getCiTags(ciProviderInfo)

    then:
    ciTags == expectedTags
  }

  def "test workspace is null if target folder does not exist"() {
    when:
    def ciProviderInfo = new UnknownCIInfo("this-target-folder-does-not-exist", workspaceForTests)
    def ciTagsProvider = new CITagsProviderImpl(GIT_FOLDER_FOR_TESTS)
    def ciTags = ciTagsProvider.getCiTags(ciProviderInfo)

    then:
    ciTags.get("$Tags.CI_WORKSPACE_PATH") == null
  }

  def "test isCi is false"() {
    when:
    def provider = instanceProvider()

    then:
    !provider.isCI()
  }
}
