package datadog.trace.instrumentation.tomcat7;

import datadog.trace.api.Config;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ParameterCollector {
  boolean isEmpty();

  void put(String key, String value);

  void put(String key, String[] values);

  Map<String, List<String>> getMap();

  void addPart(Object part);

  List<String> getFilenames();

  List<String> getContents();

  class ParameterCollectorNoop implements ParameterCollector {
    public static final ParameterCollector INSTANCE = new ParameterCollectorNoop();

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public void put(String key, String value) {}

    @Override
    public void put(String key, String[] values) {}

    @Override
    public Map<String, List<String>> getMap() {
      return Collections.emptyMap();
    }

    @Override
    public void addPart(Object part) {}

    @Override
    public List<String> getFilenames() {
      return Collections.emptyList();
    }

    @Override
    public List<String> getContents() {
      return Collections.emptyList();
    }
  }

  class ParameterCollectorImpl implements ParameterCollector {
    private static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
    private static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

    private final boolean inspectContent;
    private Map<String, List<String>> map;
    private List<String> filenames;
    private List<String> contents;

    public ParameterCollectorImpl(boolean inspectContent) {
      this.inspectContent = inspectContent;
    }

    public boolean isEmpty() {
      return map == null;
    }

    public void put(String key, String value) {
      if (map == null) {
        map = new HashMap<>();
      }
      List<String> strings = map.get(key);
      if (strings == null) {
        strings = new ArrayList<>();
        map.put(key, strings);
      }
      strings.add(value);
    }

    public void put(String key, String[] values) {
      if (map == null) {
        map = new HashMap<>();
      }
      List<String> strings = map.get(key);
      if (strings == null) {
        strings = new ArrayList<>();
        map.put(key, strings);
      }
      for (String value : values) {
        strings.add(value);
      }
    }

    @Override
    public Map<String, List<String>> getMap() {
      return map;
    }

    @Override
    public void addPart(Object part) {
      try {
        String filename = getFilename(part);
        // null means no filename parameter at all → form field, skip entirely.
        // empty string means filename="" was sent → file upload without a name, still inspect.
        if (filename == null) {
          return;
        }
        if (!filename.isEmpty()) {
          if (filenames == null) {
            filenames = new ArrayList<>();
          }
          filenames.add(filename);
        }
        if (inspectContent) {
          if (contents == null) {
            contents = new ArrayList<>();
          }
          if (contents.size() < MAX_FILES_TO_INSPECT) {
            contents.add(readContent(part));
          }
        }
      } catch (Throwable ignored) {
      }
    }

    @Override
    public List<String> getFilenames() {
      return filenames != null ? filenames : Collections.<String>emptyList();
    }

    @Override
    public List<String> getContents() {
      return contents != null ? contents : Collections.<String>emptyList();
    }

    // Entry caches (class → method) stored as a single volatile write for safe publication.
    // Keyed by Part concrete class; re-resolved when the class changes (different Tomcat version).
    private static volatile Map.Entry<Class<?>, Method> cachedInputStreamEntry;
    private static volatile Map.Entry<Class<?>, Method> cachedFilenameEntry;

    private static String readContent(Object part) {
      try {
        Class<?> partClass = part.getClass();
        Map.Entry<Class<?>, Method> entry = cachedInputStreamEntry;
        Method m;
        if (entry == null || entry.getKey() != partClass) {
          m = partClass.getMethod("getInputStream");
          cachedInputStreamEntry = new AbstractMap.SimpleImmutableEntry<>(partClass, m);
        } else {
          m = entry.getValue();
        }
        try (InputStream is = (InputStream) m.invoke(part)) {
          byte[] buf = new byte[MAX_CONTENT_BYTES];
          int total = 0;
          int n;
          while (total < MAX_CONTENT_BYTES
              && (n = is.read(buf, total, MAX_CONTENT_BYTES - total)) != -1) {
            total += n;
          }
          return new String(buf, 0, total, StandardCharsets.ISO_8859_1);
        }
      } catch (Exception ignored) {
        return "";
      }
    }

    private static String getFilename(Object part) {
      Class<?> partClass = part.getClass();
      Map.Entry<Class<?>, Method> entry = cachedFilenameEntry;
      Method m;
      if (entry == null || entry.getKey() != partClass) {
        m = null;
        // Try getSubmittedFileName() first — Servlet 3.1+ / Tomcat 8+ (both javax and jakarta)
        try {
          m = partClass.getMethod("getSubmittedFileName");
        } catch (Exception ignored) {
        }
        if (m == null) {
          // Fall back to getFilename() — Tomcat 7 ApplicationPart specific
          try {
            m = partClass.getMethod("getFilename");
          } catch (Exception ignored) {
          }
        }
        // null value in the entry means "no filename method on this class"
        cachedFilenameEntry = new AbstractMap.SimpleImmutableEntry<>(partClass, m);
      } else {
        m = entry.getValue();
      }
      if (m == null) {
        return null;
      }
      try {
        return (String) m.invoke(part);
      } catch (Exception ignored) {
        return null;
      }
    }
  }
}
