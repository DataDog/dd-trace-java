package com.datadog.appsec.config;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MergedAsmData {
  public static final String KEY_BUNDLED_DATA = "<data bundled in config>";

  private final Map<String /* cfg key */, AppSecData> configs;
  private AppSecData mergedData;

  public MergedAsmData(Map<String, AppSecData> configs) {
    this.configs = configs;
  }

  public void addConfig(String cfgKey, AppSecData config) {
    this.configs.put(cfgKey, config);
    this.mergedData = null;
  }

  public void removeConfig(String cfgKey) {
    this.configs.remove(cfgKey);
    this.mergedData = null;
  }

  /*
   * Each config key is associated with a list of maps like this:
   *
   * - id: ip_data
   *   type: ip_with_expiration
   *   data:
   *     - value: 192.168.1.1
   *       expiration: 555
   */
  public AppSecData getMergedData() throws InvalidAsmDataException {
    if (mergedData != null) {
      return mergedData;
    }

    try {
      this.mergedData = new AppSecData();
      this.mergedData.setRules(buildMergedData(groupById(AppSecData::getRules)));
      this.mergedData.setExclusion(buildMergedData(groupById(AppSecData::getExclusion)));
    } catch (InvalidAsmDataException iade) {
      throw iade;
    } catch (RuntimeException rte) {
      throw new InvalidAsmDataException(rte);
    }

    return this.mergedData;
  }

  /** map of id -> list of maps across all the configs with such id */
  private Map<String, List<Map<String, Object>>> groupById(
      final Function<AppSecData, List<Map<String, Object>>> property) {
    return configs.values().stream()
        .map(property)
        .map(it -> it == null ? Collections.<Map<String, Object>>emptyList() : it)
        .flatMap(Collection::stream)
        .collect(groupingBy(d -> (String) d.get("id")));
  }

  private List<Map<String, Object>> buildMergedData(
      Map<String, List<Map<String, Object>>> dataPerId) {
    return dataPerId.entrySet().stream()
        .map(
            idToMapList -> {
              String id = idToMapList.getKey();
              List<Map<String, Object>> mapList = idToMapList.getValue();
              if (mapList.size() == 0) {
                return null;
              }
              Set<String> types =
                  mapList.stream().map(m -> (String) m.get("type")).collect(Collectors.toSet());
              if (types.size() > 1) {
                throw new InvalidAsmDataException(
                    "multiple types of data for data id " + id + ": " + types);
              }
              String type = types.iterator().next();

              Stream<Map<String, Object>> allDataEntries =
                  mapList.stream()
                      .flatMap(m -> ((List<Map<String, Object>>) m.get("data")).stream());
              HashMap<String, Object> merged = new HashMap<>();
              merged.put("id", id);
              merged.put("type", type);

              if ("ip_with_expiration".equals(type) || "data_with_expiration".equals(type)) {
                merged.put("data", mergeExpirationData(allDataEntries));
              } else {
                // just concatenate the data
                List<Map<String, Object>> allData = allDataEntries.collect(toList());
                merged.put("data", allData);
              }
              return merged;
            })
        .collect(toList());
  }

  private List<Map<String, Object>> mergeExpirationData(Stream<Map<String, Object>> data) {
    Map<Object, Long> expirations = new HashMap<>();
    data.forEach(
        d -> {
          Object value = d.get("value");
          Long expiration = null;
          Object expirationValue = d.get("expiration");
          if (expirationValue instanceof String) {
            expirationValue = Long.parseLong((String) expirationValue);
          }
          if (expirationValue != null) {
            expiration = ((Number) expirationValue).longValue();
          }
          if (!expirations.containsKey(value)) {
            expirations.put(value, expiration);
          } else {
            Long prevExpiration = expirations.get(value);
            if (prevExpiration != null) {
              if (expiration == null || prevExpiration < expiration) {
                expirations.put(value, expiration);
              }
            }
          }
        });

    return expirations.entrySet().stream()
        .map(
            e -> {
              HashMap<String, Object> point = new HashMap<>();
              point.put("value", e.getKey());
              if (e.getValue() != null) {
                point.put("expiration", e.getValue());
              }
              return point;
            })
        .collect(toList());
  }

  public class InvalidAsmDataException extends RuntimeException {
    public InvalidAsmDataException(String s) {
      super(s);
    }

    public InvalidAsmDataException(RuntimeException rte) {
      super(rte);
    }
  }
}
