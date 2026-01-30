package datadog.trace.api;

import datadog.environment.EnvironmentVariables;
import datadog.environment.SystemProperties;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessTags {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProcessTags.class);
  private static boolean enabled = Config.get().isExperimentalPropagateProcessTagsEnabled();
  public static final String CLUSTER_NAME = "cluster.name";
  public static final String SERVER_NAME = "server.name";
  public static final String SERVER_TYPE = "server.type";
  public static final String ENTRYPOINT_NAME = "entrypoint.name";
  public static final String ENTRYPOINT_BASEDIR = "entrypoint.basedir";
  public static final String ENTRYPOINT_WORKDIR = "entrypoint.workdir";

  // visible for testing
  static Function<String, String> envGetter = EnvironmentVariables::get;

  private static class Lazy {
    // the tags are used to compute a hash for dsm hence that map must be sorted.
    static final SortedMap<String, String> TAGS = loadTags(Config.get());
    static volatile UTF8BytesString serializedForm;
    static volatile List<UTF8BytesString> utf8ListForm;
    static volatile List<String> stringListForm;

    private static SortedMap<String, String> loadTags(final Config config) {
      SortedMap<String, String> tags = new TreeMap<>();
      if (enabled) {
        try {
          fillBaseTags(tags);
          fillServiceNameTags(tags, config);
          fillJeeTags(tags);
        } catch (Throwable t) {
          LOGGER.debug("Unable to calculate default process tags", t);
        }
      }
      return tags;
    }

    private static void fillJeeTags(SortedMap<String, String> tags) {
      if (fillJbossTags(tags)) {
        return;
      }
      fillWebsphereTags(tags);
    }

    private static void insertTagFromSysPropIfPresent(
        Map<String, String> tags, String propKey, String tagKey) {
      String value = SystemProperties.get(propKey);
      if (value != null) {
        tags.put(tagKey, value);
      }
    }

    private static boolean insertTagFromEnvIfPresent(
        Map<String, String> tags, String envKey, String tagKey) {
      try {
        String value = envGetter.apply(envKey);
        if (value != null) {
          tags.put(tagKey, value);
          return true;
        }
      } catch (Throwable ignored) {
      }
      return false;
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

    private static boolean hasSystemProperty(String propKey) {
      return SystemProperties.get(propKey) != null;
    }

    private static void fillBaseTags(Map<String, String> tags) {
      final CapturedEnvironment.ProcessInfo processInfo =
          CapturedEnvironment.get().getProcessInfo();
      if (processInfo.mainClass != null) {
        tags.put(ENTRYPOINT_NAME, processInfo.mainClass);
        tags.put("entrypoint.type", "class");
      }
      if (processInfo.jarFile != null) {
        final String jarName = processInfo.jarFile.getName();
        tags.put(ENTRYPOINT_NAME, jarName.substring(0, jarName.length() - 4)); // strip .jar
        tags.put("entrypoint.type", "jar");
        insertLastPathSegmentIfPresent(tags, processInfo.jarFile.getParent(), ENTRYPOINT_BASEDIR);
      }
      insertLastPathSegmentIfPresent(tags, SystemProperties.get("user.dir"), ENTRYPOINT_WORKDIR);
    }

    private static void fillServiceNameTags(final Map<String, String> tags, final Config config) {
      if (config.isServiceNameSetByUser()) {
        tags.put("svc.user", "1");
      } else {
        tags.put("svc.auto", config.getServiceName());
      }
    }

    private static boolean fillJbossTags(Map<String, String> tags) {
      if (insertLastPathSegmentIfPresent(
          tags, SystemProperties.get("jboss.home.dir"), "jboss.home")) {
        insertTagFromSysPropIfPresent(tags, "jboss.server.name", SERVER_NAME);
        tags.put("jboss.mode", hasSystemProperty("[Standalone]") ? "standalone" : "domain");
        tags.put(SERVER_TYPE, "jboss");
        return true;
      }
      return false;
    }

    private static boolean fillWebsphereTags(Map<String, String> tags) {
      if (insertTagFromEnvIfPresent(tags, "WAS_CELL", CLUSTER_NAME)) {
        insertTagFromEnvIfPresent(tags, "SERVER_NAME", SERVER_NAME);
        tags.put(SERVER_TYPE, "websphere");
        return true;
      }
      return false;
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
                            entry.getKey()
                                + ":"
                                + TraceUtils.normalizeTagValue(
                                    entry.getValue().replace(':', '_'))));
        utf8ListForm = Collections.unmodifiableList(tagStream.collect(Collectors.toList()));
        stringListForm =
            Collections.unmodifiableList(
                utf8ListForm.stream().map(UTF8BytesString::toString).collect(Collectors.toList()));
        serializedForm = UTF8BytesString.create(String.join(",", utf8ListForm));
      }
    }
  }

  private ProcessTags() {}

  public static boolean isEnabled() {
    return enabled;
  }

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
      Lazy.TAGS.putAll(Lazy.loadTags(config));
    }
  }
}
