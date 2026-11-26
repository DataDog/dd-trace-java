package datadog.trace.civisibility.git.tree;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.civisibility.utils.ShellCommandExecutor.ShellCommandFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitRepoUnshallowTest {
  private static final String SELECTED = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String UPSTREAM = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  private final GitClient gitClient = mock(GitClient.class);
  private final Config config = mock(Config.class);
  private final GitRepoUnshallow unshallow = new GitRepoUnshallow(config, gitClient);

  @BeforeEach
  void setUp() throws Exception {
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(true);
    when(gitClient.isShallow()).thenReturn(true);
    when(gitClient.getUpstreamBranchSha()).thenReturn(UPSTREAM);
  }

  @Test
  void recoveredSelectedRevisionSkipsUpstreamFallbacks() throws Exception {
    doThrow(new ShellCommandFailedException(128, "no recent commits"))
        .when(gitClient)
        .unshallow(UPSTREAM);
    doThrow(new ShellCommandFailedException(128, "no recent commits"))
        .when(gitClient)
        .unshallow(null);

    assertTrue(unshallow.unshallow(SELECTED));

    verify(gitClient).unshallow(SELECTED);
    verify(gitClient, never()).getUpstreamBranchSha();
    verify(gitClient, never()).unshallow(null);
  }

  @Test
  void unavailableSelectedRevisionFallsBackToUpstream() throws Exception {
    doThrow(new ShellCommandFailedException(128, "not on remote"))
        .when(gitClient)
        .unshallow(SELECTED);

    assertTrue(unshallow.unshallow(SELECTED));

    verify(gitClient).unshallow(UPSTREAM);
  }

  @Test
  void headStillRecoversUpstreamHistory() throws Exception {
    assertTrue(unshallow.unshallow());

    verify(gitClient).unshallow(GitClient.HEAD);
    verify(gitClient).unshallow(UPSTREAM);
  }
}
