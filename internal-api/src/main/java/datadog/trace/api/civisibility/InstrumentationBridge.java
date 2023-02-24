package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.source.MethodLinesResolver;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import java.util.Map;

public abstract class InstrumentationBridge {

  private static volatile boolean CI;
  private static volatile Map<String, String> CI_TAGS;
  private static volatile Codeowners CODEOWNERS;
  private static volatile MethodLinesResolver METHOD_LINES_RESOLVER;
  private static volatile SourcePathResolver SOURCE_PATH_RESOLVER;
  private static volatile String MODULE;

  public static boolean isCi() {
    return CI;
  }

  public static void setCi(boolean ci) {
    CI = ci;
  }

  public static Map<String, String> getCiTags() {
    return CI_TAGS;
  }

  public static void setCiTags(Map<String, String> ciTags) {
    CI_TAGS = ciTags;
  }

  public static Codeowners getCodeowners() {
    return CODEOWNERS;
  }

  public static void setCodeowners(Codeowners codeowners) {
    InstrumentationBridge.CODEOWNERS = codeowners;
  }

  public static MethodLinesResolver getMethodLinesResolver() {
    return METHOD_LINES_RESOLVER;
  }

  public static void setMethodLinesResolver(MethodLinesResolver methodLinesResolver) {
    METHOD_LINES_RESOLVER = methodLinesResolver;
  }

  public static SourcePathResolver getSourcePathResolver() {
    return SOURCE_PATH_RESOLVER;
  }

  public static void setSourcePathResolver(SourcePathResolver sourcePathResolver) {
    SOURCE_PATH_RESOLVER = sourcePathResolver;
  }

  public static String getModule() {
    return MODULE;
  }

  public static void setModule(String MODULE) {
    InstrumentationBridge.MODULE = MODULE;
  }
}
