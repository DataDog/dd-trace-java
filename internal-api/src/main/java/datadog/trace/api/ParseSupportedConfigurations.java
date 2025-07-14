package datadog.trace.api;

import datadog.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class ParseSupportedConfigurations {
  public final Map<String, Object> fileData;
  public static Map<String, String> aliasMapping;
  public static final List<String> supportedConfigurations;
  public static final Map<String, String> deprecatedConfigurations;
  private static final Logger log = LoggerFactory.getLogger(ParseSupportedConfigurations.class);

  public ParseSupportedConfigurations(String filename) {
    String jsonString;
    try {
      InputStream in = getClass().getClassLoader().getResourceAsStream(filename);
      jsonString = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
      fileData = JsonMapper.fromJsonToMap(jsonString);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read " + filename, e);
    }
    supportedConfigurations = new ArrayList<>(((Map<String, List<String>>) fileData.get("supportedConfigurations")).keySet());
    aliasToConfig();
    deprecatedConfigurations = (Map<String, String>) fileData.get("deprecated");
  }

  private void aliasToConfig(){
    aliasMapping = new HashMap<>();
    Map<String, List<String>> aliases = (Map<String, List<String>>) fileData.get("aliases");
    for (String env : aliases.keySet()){
      for (String alias : aliases.get(env)){
        if (aliasMapping.containsKey(alias)){
          log.info("{} is listed as an alias of {} when it already exists as an alias of {}", alias, env, aliasMapping.get(alias));
        } else {
          aliasMapping.put(alias, env);
        }
      }
    }
  }



}
