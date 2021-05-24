package com.datadog.appsec.config;

import java.util.List;

public class Rule {
  public Integer id;
  public String name;
  public List<Step> steps;
  public Action action;
}
