package datadog.trace.civisibility.git

import datadog.trace.civisibility.git.tree.GitClient
import spock.lang.Specification

import java.nio.file.Paths

class CILocalGitInfoBuilderTest extends Specification {

  def "returns empty git info when repository path is not specified"() {
    setup:
    def gitClientFactory = Stub(GitClient.Factory)
    gitClientFactory.create(_) >> Stub(GitClient)

    def builder = new CILocalGitInfoBuilder(gitClientFactory,".git")

    when:
    def gitInfo = builder.build(null)

    then:
    gitInfo != null
    gitInfo.isEmpty()
  }

  def "parses git info"() {
    setup:
    def gitClientFactory = Stub(GitClient.Factory)
    gitClientFactory.create(_) >> Stub(GitClient)

    def builder = new CILocalGitInfoBuilder(gitClientFactory, "git_folder_for_tests")
    def workspace = resolve("ci/ci_workspace_for_tests")

    when:
    def gitInfo = builder.build(workspace)

    then:
    gitInfo != null
    !gitInfo.isEmpty()
    gitInfo.repositoryURL == "https://some-host/some-user/some-repo.git"
    gitInfo.branch == "master"
    gitInfo.commit.sha == "0797c248e019314fc1d91a483e859b32f4509953"
    gitInfo.commit.fullMessage == "This is a commit message\n"
    gitInfo.commit.author.name == "John Doe"
    gitInfo.commit.author.email == "john@doe.com"
    gitInfo.commit.author.iso8601Date == "2021-02-12T13:47:48.000Z"
    gitInfo.commit.committer.name == "Jane Doe"
    gitInfo.commit.committer.email == "jane@doe.com"
    gitInfo.commit.committer.iso8601Date == "2021-02-12T13:48:44.000Z"
  }

  def resolve(workspace) {
    Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
  }
}
