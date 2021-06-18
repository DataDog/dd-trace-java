package com.datadog.appsec.config;

import java.util.List;
import java.util.Map;

public class Event {
  public String id;
  public String name;
  public Map<String, String> tags;  // optional
  public List<Condition> conditions;
  public List<String> transformers;  // optional
  public Action action;
}
