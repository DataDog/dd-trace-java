package datadog.trace.api;

import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.TraceUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessTags {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProcessTags.class);
  private static boolean enabled = Config.get().isExperimentalPropagateProcessTagsEnabled();

  private static class Lazy {
    // the tags are used to compute a hash for dsm hence that map must be sorted.
    static final SortedMap<String, String> TAGS = loadTags();
    static volatile UTF8BytesString serializedForm;
    static volatile List<UTF8BytesString> utf8ListForm;
    static volatile List<String> stringListForm;

    private static SortedMap<String, String> loadTags() {
      SortedMap<String, String> tags = new TreeMap<>();
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
      if (serializedForm != null || TAGS.isEmpty()) {
        return;
      }
      synchronized (Lazy.TAGS) {
        final Stream<UTF8BytesString> tagStream =
            TAGS.entrySet().stream()
                .map(
                    entry ->
                        UTF8BytesString.create(
                            entry.getKey() + ":" + TraceUtils.normalizeTag(entry.getValue())));
        utf8ListForm = Collections.unmodifiableList(tagStream.collect(Collectors.toList()));
        stringListForm =
            Collections.unmodifiableList(
                utf8ListForm.stream().map(UTF8BytesString::toString).collect(Collectors.toList()));
        serializedForm = UTF8BytesString.create(String.join(",", utf8ListForm));
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
        Lazy.stringListForm = null;
        Lazy.utf8ListForm = null;
      }
    }
  }

  public static List<UTF8BytesString> getTagsAsUTF8ByteStringList() {
    if (!enabled) {
      return null;
    }
    final List<UTF8BytesString> listForm = Lazy.utf8ListForm;
    if (listForm != null) {
      return listForm;
    }
    Lazy.calculate();
    return Lazy.utf8ListForm;
  }

  public static List<String> getTagsAsStringList() {
    if (!enabled) {
      return null;
    }
    final List<String> listForm = Lazy.stringListForm;
    if (listForm != null) {
      return listForm;
    }
    Lazy.calculate();
    return Lazy.stringListForm;
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
      Lazy.stringListForm = null;
      Lazy.utf8ListForm = null;
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
