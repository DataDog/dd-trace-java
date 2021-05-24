package com.datadog.appsec.config;

import java.util.List;

public class Step {
  public Operation operation;
  public Target targets;
  public List<String> transformers;
}
