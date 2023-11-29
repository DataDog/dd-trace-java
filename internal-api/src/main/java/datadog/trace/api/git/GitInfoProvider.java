package datadog.trace.api.git;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.util.Strings;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
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
    Evaluator evaluator = new Evaluator(repositoryPath, builders);
    return new GitInfo(
        evaluator.get(
            gi -> GitUtils.filterSensitiveInfo(gi.getRepositoryURL()),
            GitInfoProvider::validateGitRemoteUrl),
        evaluator.get(GitInfo::getBranch, Strings::isNotBlank),
        evaluator.get(GitInfo::getTag, Strings::isNotBlank),
        new CommitInfo(
            evaluator.get(gi1 -> gi1.getCommit().getSha(), Strings::isNotBlank),
            new PersonInfo(
                evaluator.getIfCommitShaMatches(
                    gi -> gi.getCommit().getAuthor().getName(), Strings::isNotBlank),
                evaluator.getIfCommitShaMatches(
                    gi -> gi.getCommit().getAuthor().getEmail(), Strings::isNotBlank),
                evaluator.getIfCommitShaMatches(
                    gi -> gi.getCommit().getAuthor().getIso8601Date(), Strings::isNotBlank)),
            new PersonInfo(
                evaluator.getIfCommitShaMatches(
                    gi -> gi.getCommit().getCommitter().getName(), Strings::isNotBlank),
                evaluator.getIfCommitShaMatches(
                    gi -> gi.getCommit().getCommitter().getEmail(), Strings::isNotBlank),
                evaluator.getIfCommitShaMatches(
                    gi -> gi.getCommit().getCommitter().getIso8601Date(), Strings::isNotBlank)),
            evaluator.getIfCommitShaMatches(
                gi -> gi.getCommit().getFullMessage(), Strings::isNotBlank)));
  }

  private static boolean validateGitRemoteUrl(String s) {
    // we cannot work with URL that uses "file://" protocol
    return Strings.isNotBlank(s) && !s.startsWith("file:");
  }

  /**
   * Uses provided GitInfoBuilder instances to get GitInfo data.
   *
   * <p>Provided builders are sorted according to priority: those builders that are first in the
   * list have higher priority.
   *
   * <p>GitInfo is evaluated at most once for each builder, and the evaluation is lazy: if all
   * required info can be retrieved from a higher-priority builder, those with lower priority will
   * not be evaluated.
   */
  private static final class Evaluator {
    private final String repositoryPath;
    private final Map<GitInfoBuilder, GitInfo> infos;

    private Evaluator(String repositoryPath, Collection<GitInfoBuilder> builders) {
      this.repositoryPath = repositoryPath;
      this.infos = new LinkedHashMap<>();
      for (GitInfoBuilder builder : builders) {
        infos.put(builder, null);
      }
    }

    private String get(Function<GitInfo, String> function, Predicate<String> validator) {
      return get(function, validator, false);
    }

    /**
     * If a builder with a higher priority has commit SHA that differs from that of a builder with
     * lower priority, lower-priority info will be ignored.
     */
    private String getIfCommitShaMatches(
        Function<GitInfo, String> function, Predicate<String> validator) {
      return get(function, validator, true);
    }

    private String get(
        Function<GitInfo, String> function,
        Predicate<String> validator,
        boolean checkShaIntegrity) {
      String commitSha = null;
      for (Map.Entry<GitInfoBuilder, GitInfo> e : infos.entrySet()) {
        GitInfo info = e.getValue();
        if (info == null) {
          GitInfoBuilder builder = e.getKey();
          info = builder.build(repositoryPath);
          e.setValue(info);
        }

        if (checkShaIntegrity) {
          CommitInfo currentCommit = info.getCommit();
          String currentCommitSha = currentCommit != null ? currentCommit.getSha() : null;
          if (Strings.isNotBlank(currentCommitSha)) {
            if (commitSha == null) {
              commitSha = currentCommitSha;
            } else if (!commitSha.equals(currentCommitSha)) {
              // We already have a commit SHA from source that has higher priority.
              // Commit SHA from current source is different, so we have to skip it
              continue;
            }
          }
        }

        String result = function.apply(info);
        if (validator.test(result)) {
          return result;
        }
      }
      return null;
    }
  }

  public synchronized void registerGitInfoBuilder(GitInfoBuilder builder) {
    List<GitInfoBuilder> updatedBuilders = new ArrayList<>(builders);
    updatedBuilders.add(builder);
    updatedBuilders.sort(Comparator.comparingInt(GitInfoBuilder::order));
    builders = updatedBuilders;
    gitInfoCache.clear();
  }

  public synchronized void invalidateCache() {
    gitInfoCache.clear();
  }
}
