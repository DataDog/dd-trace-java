package datadog.trace.instrumentation.tomcat7;

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
  }

  class ParameterCollectorImpl implements ParameterCollector {
    private Map<String, List<String>> map;
    private List<String> filenames;

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
      String filename = getFilename(part);
      if (filename != null && !filename.isEmpty()) {
        if (filenames == null) {
          filenames = new ArrayList<>();
        }
        filenames.add(filename);
      }
    }

    @Override
    public List<String> getFilenames() {
      return filenames != null ? filenames : Collections.<String>emptyList();
    }

    private static String getFilename(Object part) {
      // Try getSubmittedFileName() first — Servlet 3.1+ / Tomcat 8+ (both javax and jakarta)
      try {
        Method m = part.getClass().getMethod("getSubmittedFileName");
        return (String) m.invoke(part);
      } catch (Exception ignored) {
      }
      // Fall back to getFilename() — Tomcat 7 ApplicationPart specific
      try {
        Method m = part.getClass().getMethod("getFilename");
        return (String) m.invoke(part);
      } catch (Exception ignored) {
      }
      return null;
    }
  }
}
