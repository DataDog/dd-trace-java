package datadog.trace.api.git;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.util.Strings;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class GitInfoProvider {

  public static final GitInfoProvider INSTANCE;

  static {
    INSTANCE = new GitInfoProvider();
    INSTANCE.registerGitInfoBuilder(new UserSuppliedGitInfoBuilder());
  }

  private volatile Collection<GitInfoBuilder> builders = Collections.emptyList();

  // in regular cases git info has to be built only once,
  // but there is a rare exception:
  // when attaching to a Gradle Daemon,
  // it is possible to have builds from multiple repositories
  // executed in the same process;
  // 4 is chosen somewhat randomly, since we want to make memory footprint small
  // and having more than 4 builds from different repos running in parallel
  // in the same daemon is unlikely
  private final DDCache<String, GitInfo> gitInfoCache = DDCaches.newFixedSizeCache(4);

  public GitInfo getGitInfo() {
    return getGitInfo(null);
  }

  public GitInfo getGitInfo(@Nullable String repositoryPath) {
    if (repositoryPath == null) {
      repositoryPath = Paths.get("").toAbsolutePath().toString();
    }
    return gitInfoCache.computeIfAbsent(repositoryPath, this::buildGitInfo);
  }

  private GitInfo buildGitInfo(String repositoryPath) {
    List<GitInfo> infos =
        builders.stream()
            .map(builder -> builder.build(repositoryPath))
            .collect(Collectors.toList());

    String commitSha = firstNonBlank(infos, gi -> gi.getCommit().getSha());
    return new GitInfo(
        firstNonBlank(infos, gi -> GitUtils.filterSensitiveInfo(gi.getRepositoryURL())),
        firstNonBlank(infos, GitInfo::getBranch),
        firstNonBlank(infos, GitInfo::getTag),
        new CommitInfo(
            commitSha,
            new PersonInfo(
                firstNonBlankWithMatchingCommit(
                    infos, commitSha, gi -> gi.getCommit().getAuthor().getName()),
                firstNonBlankWithMatchingCommit(
                    infos, commitSha, gi -> gi.getCommit().getAuthor().getEmail()),
                firstNonBlankWithMatchingCommit(
                    infos, commitSha, gi -> gi.getCommit().getAuthor().getIso8601Date())),
            new PersonInfo(
                firstNonBlankWithMatchingCommit(
                    infos, commitSha, gi -> gi.getCommit().getCommitter().getName()),
                firstNonBlankWithMatchingCommit(
                    infos, commitSha, gi -> gi.getCommit().getCommitter().getEmail()),
                firstNonBlankWithMatchingCommit(
                    infos, commitSha, gi -> gi.getCommit().getCommitter().getIso8601Date())),
            firstNonBlankWithMatchingCommit(
                infos, commitSha, gi -> gi.getCommit().getFullMessage())));
  }

  private static String firstNonBlank(
      Iterable<GitInfo> gitInfos, Function<GitInfo, String> function) {
    for (GitInfo gitInfo : gitInfos) {
      String result = function.apply(gitInfo);
      if (Strings.isNotBlank(result)) {
        return result;
      }
    }
    return null;
  }

  private static String firstNonBlankWithMatchingCommit(
      Iterable<GitInfo> gitInfos, String commitSha, Function<GitInfo, String> function) {
    for (GitInfo gitInfo : gitInfos) {
      if (commitSha != null && !commitSha.equalsIgnoreCase(gitInfo.getCommit().getSha())) {
        continue;
      }
      String result = function.apply(gitInfo);
      if (Strings.isNotBlank(result)) {
        return result;
      }
    }
    return null;
  }

  public synchronized void registerGitInfoBuilder(GitInfoBuilder builder) {
    List<GitInfoBuilder> updatedBuilders = new ArrayList<>(builders);
    updatedBuilders.add(builder);
    updatedBuilders.sort(Comparator.comparingInt(GitInfoBuilder::order));
    builders = updatedBuilders;
    gitInfoCache.clear();
  }
}
