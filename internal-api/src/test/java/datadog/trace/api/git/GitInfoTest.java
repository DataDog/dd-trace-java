package datadog.trace.api.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GitInfoTest {

  @Test
  void testBuilderIsReturningCorrectObject() {
    GitInfo gitInfo =
        new GitInfo(
            "https://some-host/some-user/some-repo.git",
            "some-branch",
            "some-tag",
            new CommitInfo(
                "0000000000000000000000000000000000000000",
                new PersonInfo("author-name", "author@email.com", 1614169397000L, 1),
                new PersonInfo("committer-name", "committer@email.com", 1614169397000L, 1),
                "some commit message\n"));

    assertEquals("https://some-host/some-user/some-repo.git", gitInfo.getRepositoryURL());
    assertEquals("some-branch", gitInfo.getBranch());
    assertEquals("some-tag", gitInfo.getTag());
    CommitInfo commit = gitInfo.getCommit();
    assertEquals("0000000000000000000000000000000000000000", commit.getSha());
    assertEquals("some commit message\n", commit.getFullMessage());
    PersonInfo author = commit.getAuthor();
    assertEquals("author-name", author.getName());
    assertEquals("author@email.com", author.getEmail());
    assertEquals("2021-02-24T12:23:17.000Z", author.getIso8601Date());
    PersonInfo committer = commit.getCommitter();
    assertEquals("committer-name", committer.getName());
    assertEquals("committer@email.com", committer.getEmail());
    assertEquals("2021-02-24T12:23:17.000Z", committer.getIso8601Date());
  }
}
