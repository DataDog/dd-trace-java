package datadog.trace.instrumentation.tomcat7;

import datadog.trace.api.Config;
import datadog.trace.api.http.MultipartContentDecoder;
import java.io.InputStream;
import java.lang.reflect.Method;
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

    // Per-class method cache; cachedPartClass is written last to safely publish the three methods.
    // Keyed by Part concrete class; re-resolved when the class changes (different Tomcat version).
    private static volatile Class<?> cachedPartClass;
    private static volatile Method cachedGetInputStream;
    private static volatile Method cachedGetContentType;
    // getSubmittedFileName (Servlet 3.1+) or getFilename (Tomcat 7); null if neither found
    private static volatile Method cachedGetFilename;

    private static void resolveAndCacheMethods(Class<?> partClass) {
      Method getInputStream = null;
      Method getContentType = null;
      Method getFilename = null;
      try {
        getInputStream = partClass.getMethod("getInputStream");
      } catch (Exception ignored) {
      }
      try {
        getContentType = partClass.getMethod("getContentType");
      } catch (Exception ignored) {
      }
      try {
        getFilename = partClass.getMethod("getSubmittedFileName");
      } catch (Exception ignored) {
      }
      if (getFilename == null) {
        try {
          getFilename = partClass.getMethod("getFilename");
        } catch (Exception ignored) {
        }
      }
      cachedGetInputStream = getInputStream;
      cachedGetContentType = getContentType;
      cachedGetFilename = getFilename;
      cachedPartClass = partClass;
    }

    private static String readContent(Object part) {
      try {
        Class<?> partClass = part.getClass();
        if (cachedPartClass != partClass) {
          resolveAndCacheMethods(partClass);
        }
        String contentType = (String) cachedGetContentType.invoke(part);
        try (InputStream is = (InputStream) cachedGetInputStream.invoke(part)) {
          return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
        }
      } catch (Exception ignored) {
        return "";
      }
    }

    private static String getFilename(Object part) {
      Class<?> partClass = part.getClass();
      if (cachedPartClass != partClass) {
        resolveAndCacheMethods(partClass);
      }
      Method m = cachedGetFilename;
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
