package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.api.Config;
import datadog.trace.util.ClassNameTrie;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Custom class/package excludes configured by the user. */
public class CustomExcludes {
  private static final Logger log = LoggerFactory.getLogger(CustomExcludes.class);

  private CustomExcludes() {}

  private static final ClassNameTrie excludes;

  static {
    ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
    for (String name : Config.get().getExcludedClasses()) {
      builder.put(name, 1);
    }
    String excludedClassesFile = Config.get().getExcludedClassesFile();
    if (null != excludedClassesFile) {
      try {
        builder.readClassNameMapping(Paths.get(excludedClassesFile));
      } catch (Exception e) {
        log.warn("Problem reading class excludes from {}", excludedClassesFile, e);
      }
    }
    excludes = !builder.isEmpty() ? builder.buildTrie() : null;
  }

  public static boolean isExcluded(String name) {
    return excludes != null && excludes.apply(name) > 0;
  }
}
