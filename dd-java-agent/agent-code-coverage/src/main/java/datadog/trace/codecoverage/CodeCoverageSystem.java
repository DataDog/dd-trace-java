package datadog.trace.codecoverage;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.api.intake.Intake;
import datadog.trace.coverage.CoverageReportUploader;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the production code coverage product module.
 *
 * <p>Follows the tracer's standard product system pattern with a two-phase start:
 *
 * <ol>
 *   <li>{@link #start(Instrumentation)} — called during premain, <b>before</b> ByteBuddy's
 *       transformer is registered. Must not use logging, NIO, or JMX.
 *   <li>{@link #startCollector(Object, Object)} — called from a deferred callback after premain,
 *       when logging and thread scheduling are safe.
 * </ol>
 */
public final class CodeCoverageSystem {

  private static final Logger log = LoggerFactory.getLogger(CodeCoverageSystem.class);

  /**
   * Phase 1: registers the coverage {@link java.lang.instrument.ClassFileTransformer}.
   *
   * <p>Called during premain, synchronously, before ByteBuddy. The returned object is an opaque
   * handle to the transformer, passed to {@link #startCollector(Object, Object)} later.
   *
   * @param inst the JVM instrumentation service
   * @return the transformer instance (opaque; passed to {@link #startCollector})
   * @throws Exception if JaCoCo runtime initialization fails
   */
  public static Object start(Instrumentation inst) throws Exception {
    Config config = Config.get();
    String[] includes = config.getCodeCoverageIncludes();
    String[] excludes = config.getCodeCoverageExcludes();
    Predicate<String> filter = new CodeCoverageFilter(includes, excludes);
    CodeCoverageTransformer transformer = new CodeCoverageTransformer(inst, filter);
    inst.addTransformer(transformer);
    return transformer;
  }

  /**
   * Phase 2: starts the periodic coverage collector.
   *
   * <p>Called from a deferred callback after premain. Safe to use logging and thread scheduling.
   *
   * @param transformerObj the opaque transformer handle returned by {@link #start}
   * @param scoObj the SharedCommunicationObjects instance for backend communication
   */
  public static void startCollector(Object transformerObj, Object scoObj) {
    CodeCoverageTransformer transformer = (CodeCoverageTransformer) transformerObj;
    Config config = Config.get();

    // Build event tags from git info
    Map<String, Object> tags = buildGitTags();
    if (!tags.containsKey("git.commit.sha")) {
      log.warn(
          "DD_GIT_COMMIT_SHA is not set; "
              + "code coverage reports cannot be uploaded without a commit SHA");
      return;
    }

    // Create BackendApi for coverage uploads
    BackendApiFactory factory =
        new BackendApiFactory(config, (SharedCommunicationObjects) scoObj);
    BackendApi backendApi = factory.createBackendApi(Intake.CI_INTAKE);
    if (backendApi == null) {
      log.warn(
          "Cannot create backend API for code coverage uploads; "
              + "agent may not support EVP proxy");
      return;
    }

    tags.put(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE);
    String env = config.getEnv();
    if (env != null && !env.isEmpty()) {
      tags.put("runtime.env", env);
    }
    String serviceName = config.getServiceName();
    if (serviceName != null && !serviceName.isEmpty()) {
      tags.put("report.flags", Collections.singletonList("service:" + serviceName));
    }

    CoverageReportUploader uploader = new CoverageReportUploader(backendApi, tags, null);
    CodeCoverageSender sender = new CodeCoverageSender(uploader);

    CodeCoverageCollector collector =
        new CodeCoverageCollector(
            transformer,
            sender,
            config.getCodeCoverageReportIntervalSeconds(),
            config.getCodeCoverageClasspath());
    collector.start();
  }

  private static Map<String, Object> buildGitTags() {
    Map<String, Object> tags = new HashMap<>();
    GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo();
    CommitInfo commit = gitInfo.getCommit();
    if (commit != null && commit.getSha() != null) {
      tags.put("git.commit.sha", commit.getSha());
    }
    if (gitInfo.getRepositoryURL() != null) {
      tags.put("git.repository_url", gitInfo.getRepositoryURL());
    }
    if (gitInfo.getBranch() != null) {
      tags.put("git.branch", gitInfo.getBranch());
    }
    // Add author/committer info if available
    if (commit != null) {
      PersonInfo author = commit.getAuthor();
      if (author.getName() != null) {
        tags.put("git.commit.author.name", author.getName());
      }
      if (author.getEmail() != null) {
        tags.put("git.commit.author.email", author.getEmail());
      }
      if (author.getIso8601Date() != null) {
        tags.put("git.commit.author.date", author.getIso8601Date());
      }
      PersonInfo committer = commit.getCommitter();
      if (committer.getName() != null) {
        tags.put("git.commit.committer.name", committer.getName());
      }
      if (committer.getEmail() != null) {
        tags.put("git.commit.committer.email", committer.getEmail());
      }
      if (committer.getIso8601Date() != null) {
        tags.put("git.commit.committer.date", committer.getIso8601Date());
      }
      if (commit.getFullMessage() != null) {
        tags.put("git.commit.message", commit.getFullMessage());
      }
    }
    return tags;
  }

  private CodeCoverageSystem() {}
}
