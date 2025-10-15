package foo.bar.smoketest;

public interface ServletRequestTestSuite {

  String getRequestURI();

  String getPathInfo();

  String getPathTranslated();

  StringBuffer getRequestURL();
}
