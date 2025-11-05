package datadog.smoketest.controller;

public interface ServletRequestTestSuite {

  String getRequestURI();

  String getPathInfo();

  String getPathTranslated();

  StringBuffer getRequestURL();
}
