package datadog.trace.api.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

  @Test
  void contributesItsRepositoryAndCommitTagsToASpan() {
    GitInfo gitInfo =
        new GitInfo(
            "https://some-host/some-user/some-repo.git",
            "some-branch",
            "some-tag",
            new CommitInfo("0000000000000000000000000000000000000000"));
    AgentSpan span = mock(AgentSpan.class);

    gitInfo.addTo(span);

    ArgumentCaptor<TagMap.EntryReader> tags = ArgumentCaptor.forClass(TagMap.EntryReader.class);
    verify(span, times(2)).setTag(tags.capture());
    List<String> values =
        tags.getAllValues().stream()
            .map(tag -> tag.tag() + "=" + tag.stringValue())
            .collect(Collectors.toList());
    assertEquals(
        Arrays.asList(
            DDTags.INTERNAL_GIT_REPOSITORY_URL + "=https://some-host/some-user/some-repo.git",
            DDTags.INTERNAL_GIT_COMMIT_SHA + "=0000000000000000000000000000000000000000"),
        values);
  }
}
