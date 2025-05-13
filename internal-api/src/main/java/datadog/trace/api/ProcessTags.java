package datadog.trace.api;

import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.TraceUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessTags {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProcessTags.class);
  private static boolean enabled = Config.get().isExperimentalPropagateProcessTagsEnabled();

  private static class Lazy {
    static final Map<String, String> TAGS = loadTags();
    static volatile UTF8BytesString serializedForm;
    static volatile List<String> listForm;

    private static Map<String, String> loadTags() {
      Map<String, String> tags = new LinkedHashMap<>();
      if (enabled) {
        try {
          fillBaseTags(tags);
          fillJbossTags(tags);
        } catch (Throwable t) {
          LOGGER.debug("Unable to calculate default process tags", t);
        }
      }
      return tags;
    }

    private static void insertSysPropIfPresent(
        Map<String, String> tags, String propKey, String tagKey) {
      String value = System.getProperty(propKey);
      if (value != null) {
        tags.put(tagKey, value);
      }
    }

    private static boolean insertLastPathSegmentIfPresent(
        Map<String, String> tags, String path, String tagKey) {
      if (path == null || path.isEmpty()) {
        return false;
      }
      try {
        final Path p = Paths.get(path).getFileName();
        if (p != null) {
          tags.put(tagKey, p.toString());
          return true;
        }
      } catch (Throwable ignored) {
      }
      return false;
    }

    private static void fillBaseTags(Map<String, String> tags) {
      final CapturedEnvironment.ProcessInfo processInfo =
          CapturedEnvironment.get().getProcessInfo();
      if (processInfo.mainClass != null) {
        tags.put("entrypoint.name", processInfo.mainClass);
      }
      if (processInfo.jarFile != null) {
        final String jarName = processInfo.jarFile.getName();
        tags.put("entrypoint.name", jarName.substring(0, jarName.length() - 4)); // strip .jar
        insertLastPathSegmentIfPresent(tags, processInfo.jarFile.getParent(), "entrypoint.basedir");
      }

      insertLastPathSegmentIfPresent(tags, System.getProperty("user.dir"), "entrypoint.workdir");
    }

    private static void fillJbossTags(Map<String, String> tags) {
      if (insertLastPathSegmentIfPresent(
          tags, System.getProperty("jboss.home.dir"), "jboss.home")) {
        insertSysPropIfPresent(tags, "jboss.server.name", "server.name");
        tags.put(
            "jboss.mode",
            System.getProperties().containsKey("[Standalone]") ? "standalone" : "domain");
      }
    }

    static void calculate() {
      if (listForm != null || TAGS.isEmpty()) {
        return;
      }
      synchronized (Lazy.TAGS) {
        final Stream<String> tagStream =
            TAGS.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + TraceUtils.normalizeTag(entry.getValue()));
        listForm = Collections.unmodifiableList(tagStream.collect(Collectors.toList()));
        serializedForm = UTF8BytesString.create(String.join(",", listForm));
      }
    }
  }

  private ProcessTags() {}

  // need to be synchronized on writing. As optimization, it does not need to be sync on read.
  public static void addTag(String key, String value) {
    if (enabled) {
      synchronized (Lazy.TAGS) {
        Lazy.TAGS.put(key, value);
        Lazy.serializedForm = null;
        Lazy.listForm = null;
      }
    }
  }

  public static List<String> getTagsAsList() {
    if (!enabled) {
      return null;
    }
    final List<String> listForm = Lazy.listForm;
    if (listForm != null) {
      return listForm;
    }
    Lazy.calculate();
    return Lazy.listForm;
  }

  public static UTF8BytesString getTagsForSerialization() {
    if (!enabled) {
      return null;
    }
    final UTF8BytesString serializedForm = Lazy.serializedForm;
    if (serializedForm != null) {
      return serializedForm;
    }
    Lazy.calculate();
    return Lazy.serializedForm;
  }

  /** Visible for testing. */
  static void empty() {
    synchronized (Lazy.TAGS) {
      Lazy.TAGS.clear();
      Lazy.serializedForm = null;
      Lazy.listForm = null;
    }
  }

  /** Visible for testing. */
  static void reset() {
    reset(Config.get());
  }

  /** Visible for testing. */
  public static void reset(Config config) {
    synchronized (Lazy.TAGS) {
      empty();
      enabled = config.isExperimentalPropagateProcessTagsEnabled();
      Lazy.TAGS.putAll(Lazy.loadTags());
    }
  }
}
