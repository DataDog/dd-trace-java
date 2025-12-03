package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.api.InstrumenterConfig;
import java.nio.file.Paths;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Custom class/package excludes configured by the user. */
public class CustomExcludes {
  private static final Logger log = LoggerFactory.getLogger(CustomExcludes.class);

  private CustomExcludes() {}

  private static final ClassNameTrie excludes;

  static {
    List<String> excludedClasses = InstrumenterConfig.get().getExcludedClasses();
    if (excludedClasses.isEmpty()) {
      excludes = null;
    } else {
      ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
      for (String name : excludedClasses) {
        builder.put(name, 1);
      }
      String excludedClassesFile = InstrumenterConfig.get().getExcludedClassesFile();
      if (null != excludedClassesFile) {
        try {
          builder.readClassNameMapping(Paths.get(excludedClassesFile));
        } catch (Exception e) {
          log.warn("Problem reading class excludes from {}", excludedClassesFile, e);
        }
      }
      excludes = builder.buildTrie();
    }
  }

  public static boolean isExcluded(String name) {
    return excludes != null && excludes.apply(name) > 0;
  }
}
