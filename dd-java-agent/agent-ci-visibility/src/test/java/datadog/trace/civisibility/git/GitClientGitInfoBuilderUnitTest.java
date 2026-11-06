package datadog.trace.civisibility.git;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.git.tree.GitClient;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class GitClientGitInfoBuilderUnitTest {
  @Test
  void interruptedRemoteLookupAbortsAndPreservesInterruption() throws Exception {
    GitClient client = mock(GitClient.class);
    when(client.getRemoteUrl("origin")).thenThrow(new InterruptedException());
    try {
      assertEquals(GitInfo.NOOP, builder(client).build("/repo"));
      assertTrue(Thread.currentThread().isInterrupted());
      verify(client, never()).getCurrentBranch();
    } finally {
      Thread.interrupted();
    }
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  void timedOutRemoteLookupAborts() throws Exception {
    GitClient client = mock(GitClient.class);
    when(client.getRemoteUrl("origin")).thenThrow(new TimeoutException());
    assertEquals(GitInfo.NOOP, builder(client).build("/repo"));
    verify(client, never()).getCurrentBranch();
  }

  @Test
  void tagsAreOnlyInferredForAnUnambiguousDetachedCheckout() throws Exception {
    GitClient client = mock(GitClient.class);
    when(client.getCommitInfo(GitClient.HEAD, false)).thenReturn(new CommitInfo("local-sha"));
    when(client.getCurrentBranch()).thenReturn("main", "", "");
    when(client.getTags(GitClient.HEAD)).thenReturn(singletonList("v1"), asList("v1", "stable"));
    GitClientGitInfoBuilder builder = builder(client);
    assertNull(builder.build("/repo").getTag());
    assertEquals("v1", builder.build("/repo").getTag());
    assertNull(builder.build("/repo").getTag());
  }

  private GitClientGitInfoBuilder builder(GitClient client) {
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitRemoteName()).thenReturn("origin");
    GitClient.Factory factory = mock(GitClient.Factory.class);
    when(factory.create("/repo")).thenReturn(client);
    return new GitClientGitInfoBuilder(config, factory);
  }
}
