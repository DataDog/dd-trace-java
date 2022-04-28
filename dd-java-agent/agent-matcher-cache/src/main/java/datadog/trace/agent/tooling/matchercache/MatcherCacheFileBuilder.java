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

    final int javaMajorVersion = matcherCacheBuilder.getJavaMajorVersion();

    try {
      ClassCollection classes = classFinder.findClassesIn(new File(params.getJavaHome()));
      ClassCollectionLoader classLoader = new ClassCollectionLoader(classes, javaMajorVersion);
      MatcherCacheBuilder.Stats stats =
          matcherCacheBuilder.fill(classes, classLoader, classMatchers);
      log.info("Scanned JDK classes: {}", stats);
    } catch (IOException e) {
      log.error("Failed to scan JDK classes", e);
    }

    try {
      ClassCollection classes = classFinder.findClassesIn(params.getDDAgentJar());
      ClassCollectionLoader classLoader = new ClassCollectionLoader(classes, javaMajorVersion);
      MatcherCacheBuilder.Stats stats =
          matcherCacheBuilder.fill(classes, classLoader, classMatchers);
      log.info("Scanned {} classes: {}", params.getDDAgentJar(), stats);
    } catch (IOException e) {
      log.error("Failed to scan " + params.getDDAgentJar() + " classes", e);
    }

    for (String cp : params.getClassPaths()) {
      try {
        ClassCollection classes = classFinder.findClassesIn(new File(cp));
        ClassCollectionLoader classLoader = new ClassCollectionLoader(classes, javaMajorVersion);
        MatcherCacheBuilder.Stats stats =
            matcherCacheBuilder.fill(classes, classLoader, classMatchers);
        log.info("Scanned {} classes: {}", cp, stats);
      } catch (IOException e) {
        log.error("Failed to scan " + params.getDDAgentJar() + " classes", e);
      }
    }

    // TODO implement skip list
    matcherCacheBuilder.addSkippedPackage("com.sun.proxy", "<skip-list>");

    matcherCacheBuilder.optimize();
    try {
      matcherCacheBuilder.serializeBinary(new File(params.getOutputFile()));
    } catch (IOException e) {
      log.error("Failed to serialize matcher cache into " + params.getOutputFile(), e);
      throw new RuntimeException(e);
    }
  }
}
