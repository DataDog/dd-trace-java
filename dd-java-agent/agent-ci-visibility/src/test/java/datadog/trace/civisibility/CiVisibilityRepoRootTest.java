package datadog.trace.civisibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.git.tree.GitClient;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class CiVisibilityRepoRootTest {

  @Test
  void asksGitFromTheProjectDirectoryWithoutCiWorkspace() throws Exception {
    // a linked worktree outside its main checkout has no .git directory above it, and a Gradle
    // daemon's working directory is not in the repository
    Path projectDirectory = Paths.get("/checkouts/worktree/module");
    GitClient gitClient = mock(GitClient.class);
    when(gitClient.getRepoRoot()).thenReturn("/checkouts/worktree");
    GitClient.Factory gitClientFactory = mock(GitClient.Factory.class);
    when(gitClientFactory.create(projectDirectory.toString())).thenReturn(gitClient);

    String repoRoot =
        CiVisibilityRepoServices.getRepoRoot(CIInfo.NOOP, gitClientFactory, projectDirectory);

    assertEquals("/checkouts/worktree", repoRoot);
  }

  @Test
  void takesTheCiWorkspaceWithoutAskingGit() {
    CIInfo ciInfo = mock(CIInfo.class);
    when(ciInfo.getCiWorkspace()).thenReturn("/ci/workspace");
    GitClient.Factory gitClientFactory = mock(GitClient.Factory.class);

    String repoRoot =
        CiVisibilityRepoServices.getRepoRoot(
            ciInfo, gitClientFactory, Paths.get("/ci/workspace/module"));

    assertEquals("/ci/workspace", repoRoot);
    verify(gitClientFactory, never()).create(any());
  }
}
