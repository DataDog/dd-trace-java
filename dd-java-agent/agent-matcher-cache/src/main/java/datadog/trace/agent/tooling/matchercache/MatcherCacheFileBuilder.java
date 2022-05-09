package datadog.trace.agent.tooling.matchercache;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollectionLoader;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheFileBuilder {
  private static final Logger log = LoggerFactory.getLogger(MatcherCacheFileBuilder.class);

  private final ClassFinder classFinder;
  private final MatcherCacheBuilder matcherCacheBuilder;
  private final ClassMatchers classMatchers;

  public MatcherCacheFileBuilder(
      ClassFinder classFinder,
      MatcherCacheBuilder matcherCacheBuilder,
      ClassMatchers classMatchers) {
    this.classFinder = classFinder;
    this.matcherCacheBuilder = matcherCacheBuilder;
    this.classMatchers = classMatchers;
  }

  public void buildMatcherCacheFile(MatcherCacheFileBuilderParams params) {
    if (!params.validate()) {
      return;
    }

    fillFrom(new File(params.getJavaHome()));

    fillFrom(params.getDDAgentJar());
    for (String cp : params.getClassPaths()) {
      fillFrom(new File(cp));
    }

    if (params.getOutputCsvReportFile() != null) {
      try {
        matcherCacheBuilder.serializeText(new File(params.getOutputCsvReportFile()));
      } catch (IOException e) {
        log.error(
            "Failed to serialize matcher cache CSV report into " + params.getOutputCsvReportFile(),
            e);
        throw new RuntimeException(e);
      }
    }

    try {
      matcherCacheBuilder.serializeBinary(new File(params.getOutputCacheDataFile()));
    } catch (IOException e) {
      log.error(
          "Failed to serialize matcher cache data into " + params.getOutputCacheDataFile(), e);
      throw new RuntimeException(e);
    }
  }

  private void fillFrom(File classPath) {
    final int javaMajorVersion = matcherCacheBuilder.getJavaMajorVersion();
    try {
      ClassCollection classes = classFinder.findClassesIn(classPath);
      ClassCollectionLoader classLoader = new ClassCollectionLoader(classes, javaMajorVersion);
      MatcherCacheBuilder.Stats stats =
          matcherCacheBuilder.fill(classes, classLoader, classMatchers);
      log.info("Scanned {}: {}", classPath, stats);
    } catch (IOException e) {
      log.error("Failed to scan: " + classPath, e);
    }
  }
}
