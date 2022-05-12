package datadog.trace.agent.tooling.matchercache;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatcherCacheFileBuilder {
  private static final Logger log = LoggerFactory.getLogger(MatcherCacheFileBuilder.class);

  private final ClassFinder classFinder;
  private final MatcherCacheBuilder matcherCacheBuilder;

  public MatcherCacheFileBuilder(ClassFinder classFinder, MatcherCacheBuilder matcherCacheBuilder) {
    this.classFinder = classFinder;
    this.matcherCacheBuilder = matcherCacheBuilder;
  }

  public void buildMatcherCacheFile(MatcherCacheFileBuilderParams params) {
    if (!params.validate()) {
      return;
    }

    final File jdkClassPath =
        new File(params.getJavaHome()); // TODO pass JDK home as an arg, and get it's version (java
    // -version)
    ClassCollection jdkClassCollection = findClassesIn(jdkClassPath);
    fillFrom(jdkClassPath, jdkClassCollection);

    ClassCollection ddAgentClassCollection = findClassesIn(params.getDDAgentJar());
    if (ddAgentClassCollection != null) {
      fillFrom(params.getDDAgentJar(), ddAgentClassCollection.withParent(jdkClassCollection));
    }

    for (String cp : params.getClassPaths()) {
      final File classPath = new File(cp);
      ClassCollection classCollection = findClassesIn(classPath);
      if (classCollection != null) {
        fillFrom(classPath, classCollection.withParent(jdkClassCollection));
      }
    }

    if (params.getOutputCsvReportFile() != null) {
      try {
        matcherCacheBuilder.serializeText(new File(params.getOutputCsvReportFile()));
        log.info("Matcher cache CSV report has been saved into " + params.getOutputCsvReportFile());
      } catch (IOException e) {
        log.error(
            "Failed to serialize matcher cache CSV report into " + params.getOutputCsvReportFile(),
            e);
        throw new RuntimeException(e);
      }
    }

    try {
      matcherCacheBuilder.serializeBinary(new File(params.getOutputCacheDataFile()));
      log.info("Matcher cache data has been saved into " + params.getOutputCacheDataFile());
    } catch (IOException e) {
      log.error(
          "Failed to serialize matcher cache data into " + params.getOutputCacheDataFile(), e);
      throw new RuntimeException(e);
    }
  }

  private ClassCollection findClassesIn(File classPath) {
    try {
      return classFinder.findClassesIn(classPath);
    } catch (IOException e) {
      log.error("Failed to scan: " + classPath, e);
    }
    return null;
  }

  private void fillFrom(File classPath, ClassCollection classCollection) {
    MatcherCacheBuilder.Stats stats = matcherCacheBuilder.fill(classCollection);
    log.info("Scanned {}: {}", classPath, stats);
  }
}
