package datadog.trace.bootstrap.instrumentation.ci

import datadog.trace.bootstrap.instrumentation.api.Tags

import java.nio.file.Path
import java.nio.file.Paths

class UnknownCIInfoTest extends CIProviderInfoTest {

  def workspaceForTests = Paths.get(getClass().getClassLoader().getResource(CI_WORKSPACE_PATH_FOR_TESTS).toURI())

  @Override
  CIProviderInfo instanceProvider() {
    return new UnknownCIInfo() {
        @Override
        protected Path getCurrentPath() {
          return workspaceForTests
        }

        @Override
        protected String getTargetFolder() {
          return getGitFolderName()
        }

        @Override
        protected String getGitFolderName() {
          return GIT_FOLDER_FOR_TESTS
        }
      }
  }

  @Override
  String getProviderName() {
    return UnknownCIInfo.UNKNOWN_PROVIDER_NAME
  }

  def "test info is set properly from local workspace"() {
    setup:
    def expectedTags = [
      (Tags.CI_WORKSPACE_PATH):"${workspaceForTests.toString()}",
      (Tags.GIT_REPOSITORY_URL):"https://some-host/some-user/some-repo.git",
      (Tags.GIT_BRANCH):"master",
      (Tags.GIT_COMMIT_SHA):"0797c248e019314fc1d91a483e859b32f4509953",
      (Tags.GIT_COMMIT_MESSAGE):"This is a commit message\n",
      (Tags.GIT_COMMIT_AUTHOR_NAME):"John Doe",
      (Tags.GIT_COMMIT_AUTHOR_EMAIL):"john@doe.com",
      (Tags.GIT_COMMIT_AUTHOR_DATE):"2021-02-12T13:47:48.000Z",
      (Tags.GIT_COMMIT_COMMITTER_NAME):"Jane Doe",
      (Tags.GIT_COMMIT_COMMITTER_EMAIL):"jane@doe.com",
      (Tags.GIT_COMMIT_COMMITTER_DATE):"2021-02-12T13:48:44.000Z"
    ]

    when:
    def ciInfo = instanceProvider()

    then:
    ciInfo.ciTags == expectedTags
  }

  def "test workspace is null if target folder does not exist"(){
    when:
    def ciInfo = new UnknownCIInfo() {
        @Override
        protected String getTargetFolder() {
          return "this-target-folder-does-not-exist"
        }
      }

    then:
    ciInfo.ciTags.get("$Tags.CI_WORKSPACE_PATH") == null
  }

  def "test isCi is false"() {
    when:
    def provider = instanceProvider()

    then:
    !provider.isCI()
  }
}
