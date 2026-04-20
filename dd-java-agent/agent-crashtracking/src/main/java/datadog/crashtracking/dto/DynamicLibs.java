package datadog.crashtracking.dto;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DynamicLibs {
  public final String name;
  public final List<String> lines;

  public DynamicLibs(String name, List<String> lines) {
    this.name = name;
    this.lines = lines;
  }

  public static class JsonAdapter {
    @ToJson
    Map<String, List<String>> toJson(DynamicLibs dynamicLibs) {
      return Collections.singletonMap(dynamicLibs.name, dynamicLibs.lines);
    }

    @FromJson
    DynamicLibs fromJson(Map<String, List<String>> map) {
      Map.Entry<String, List<String>> entry = map.entrySet().iterator().next();
      return new DynamicLibs(entry.getKey(), entry.getValue());
    }
  }
}
