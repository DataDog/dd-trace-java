package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class ApplicationModuleImpl extends SinkModuleBase implements ApplicationModule {

  private static final String CONTEXT_LOADER_LISTENER_PATTERN =
      "org\\.springframework\\.web\\.context\\.ContextLoaderListener";

  private static final String CONTEXT_LOADER_LISTENER_VALUE =
      "org.springframework.web.context.ContextLoaderListener";

  private static final String DISPATCHER_SERVLET_PATTERN =
      "org\\.springframework\\.web\\.servlet\\.DispatcherServlet";

  private static final String DISPATCHER_SERVLET_VALUE =
      "org.springframework.web.servlet.DispatcherServlet";

  private static final String DEFAULT_HTML_ESCAPE = "defaultHtmlEscape";

  private static final String TOMCAT_MANAGER_APPLICATION = "Tomcat Manager Application";

  private static final String LISTINGS_PATTERN = "<param-name>listings</param-name>";

  private static final String SESSION_TIMEOUT_START_TAG = "<session-timeout>";

  private static final String SESSION_TIMEOUT_END_TAG = "</session-timeout>";

  private static final String SECURITY_CONSTRAINT_START_TAG = "<security-constraint>";

  private static final String SECURITY_CONSTRAINT_END_TAG = "</security-constraint>";

  public static final String PARAM_VALUE_START_TAG = "<param-value>";

  public static final String PARAM_VALUE_END_TAG = "</param-value>";

  public static final String WEB_INF = "WEB-INF";

  public static final String WEB_XML = "web.xml";

  private static final String REGEX =
      String.join(
          "|",
          CONTEXT_LOADER_LISTENER_PATTERN,
          DISPATCHER_SERVLET_PATTERN,
          DEFAULT_HTML_ESCAPE,
          TOMCAT_MANAGER_APPLICATION,
          LISTINGS_PATTERN,
          SESSION_TIMEOUT_START_TAG,
          SECURITY_CONSTRAINT_START_TAG);

  private static final Pattern PATTERN = Pattern.compile(REGEX);

  private static final int NO_LINE = -1;

  public ApplicationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRealPath(final @Nullable String realPath) {

    if (realPath == null) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();

    checkInsecureJSPLayout(realPath, span);

    String webXmlContent = webXmlContent(realPath);
    if (webXmlContent == null) {
      return;
    }

    int defaultHtmlEscapeIndex = -1;
    boolean isSpring = false;

    Matcher matcher = PATTERN.matcher(webXmlContent);
    while (matcher.find()) {
      String match = matcher.group();
      switch (match) {
        case DISPATCHER_SERVLET_VALUE:
        case CONTEXT_LOADER_LISTENER_VALUE:
          isSpring = true;
          break;
        case DEFAULT_HTML_ESCAPE:
          defaultHtmlEscapeIndex = matcher.start();
          break;
        case TOMCAT_MANAGER_APPLICATION:
          reportAdminConsoleActive(span);
          break;
        case LISTINGS_PATTERN:
          checkDirectoryListingLeak(webXmlContent, matcher.start(), span);
          break;
        case SESSION_TIMEOUT_START_TAG:
          checkSessionTimeOut(webXmlContent, matcher.start(), span);
          break;
        case SECURITY_CONSTRAINT_START_TAG:
          checkVerbTampering(webXmlContent, matcher.start(), span);
          break;
        default:
          break;
      }
    }

    if (isSpring) {
      checkDefaultHtmlEscapeInvalid(webXmlContent, span, defaultHtmlEscapeIndex);
    }
  }

  private void checkDefaultHtmlEscapeInvalid(
      String webXmlContent, AgentSpan span, int defaultHtmlEscapeIndex) {
    if (defaultHtmlEscapeIndex != -1) {
      int start =
          webXmlContent.indexOf(PARAM_VALUE_START_TAG, defaultHtmlEscapeIndex)
              + PARAM_VALUE_START_TAG.length();
      String value =
          webXmlContent.substring(start, webXmlContent.indexOf(PARAM_VALUE_END_TAG, start));
      if (!value.trim().toLowerCase().equals("true")) {
        report(
            span,
            VulnerabilityType.DEFAULT_HTML_ESCAPE_INVALID,
            "defaultHtmlEscape tag should be true",
            getLine(webXmlContent, start));
      }
    } else {
      report(
          span,
          VulnerabilityType.DEFAULT_HTML_ESCAPE_INVALID,
          "defaultHtmlEscape tag should be set",
          NO_LINE);
    }
  }

  private void reportAdminConsoleActive(AgentSpan span) {
    report(span, VulnerabilityType.ADMIN_CONSOLE_ACTIVE, "Tomcat Manager Application", NO_LINE);
  }

  private void checkDirectoryListingLeak(
      final String webXmlContent, int index, final AgentSpan span) {
    int valueIndex =
        webXmlContent.indexOf(PARAM_VALUE_START_TAG, index) + PARAM_VALUE_START_TAG.length();
    int valueLast = webXmlContent.indexOf(PARAM_VALUE_END_TAG, valueIndex);
    String data = webXmlContent.substring(valueIndex, valueLast);
    if (data.trim().toLowerCase().equals("true")) {
      report(
          span,
          VulnerabilityType.DIRECTORY_LISTING_LEAK,
          "Directory listings configured",
          getLine(webXmlContent, index));
    }
  }

  private void checkSessionTimeOut(final String webXmlContent, int index, final AgentSpan span) {
    try {
      String innerText =
          webXmlContent
              .substring(
                  index + SESSION_TIMEOUT_START_TAG.length(),
                  webXmlContent.indexOf(SESSION_TIMEOUT_END_TAG, index))
              .trim();
      int timeoutValue = Integer.parseInt(innerText);
      if (timeoutValue > 30 || timeoutValue == -1) {
        report(
            span,
            VulnerabilityType.SESSION_TIMEOUT,
            "Found vulnerable timeout value: " + timeoutValue,
            getLine(webXmlContent, index));
      }
    } catch (NumberFormatException e) {
      // Nothing to do
    }
  }

  private void checkVerbTampering(final String webXmlContent, int index, final AgentSpan span) {
    String innerText =
        webXmlContent
            .substring(
                index + SECURITY_CONSTRAINT_START_TAG.length(),
                webXmlContent.indexOf(SECURITY_CONSTRAINT_END_TAG, index))
            .trim();
    if (!innerText.contains("<http-method>")) {
      report(
          span,
          VulnerabilityType.VERB_TAMPERING,
          "http-method not defined in web.xml",
          getLine(webXmlContent, index));
    }
  }

  private int getLine(String webXmlContent, int index) {
    int line = 1;
    int limit = Math.min(index, webXmlContent.length());
    for (int i = limit; i > 0; i--) {
      if (webXmlContent.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private void report(AgentSpan span, VulnerabilityType type, String value, int line) {
    reporter.report(
        span,
        new Vulnerability(
            type, Location.forSpanAndFileAndLine(span, WEB_XML, line), new Evidence(value)));
  }

  private void checkInsecureJSPLayout(String realPath, AgentSpan span) {
    List<String> jspPaths = findRecursively(new File(realPath));
    if (jspPaths.isEmpty()) {
      return;
    }
    String result =
        jspPaths.stream()
            .map(s -> File.separatorChar + s)
            .collect(Collectors.joining(System.lineSeparator()));
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.INSECURE_JSP_LAYOUT, Location.forSpan(span), new Evidence(result)));
  }

  @Nullable
  private String webXmlContent(final String realPath) {
    if (realPath != null) {
      Path path = Paths.get(realPath + File.separatorChar + WEB_INF + File.separatorChar + WEB_XML);
      if (path.toFile().exists()) {
        try {
          return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
          // Nothing to do
        }
      }
    }
    return null;
  }

  private List<String> findRecursively(final File root) {
    List<String> jspPaths = new ArrayList<>();
    if (root.listFiles() != null) {
      for (File file : root.listFiles()) {
        if (file.isFile() && isJsp(file.getName().toLowerCase())) {
          jspPaths.add(file.getName());
        } else if (file.isDirectory() && !WEB_INF.equals(file.getName())) {
          addDirectoryJSPs("", file, jspPaths);
        }
      }
    }
    return jspPaths;
  }

  private void addDirectoryJSPs(final String path, final File dir, final List<String> jspPaths) {
    String currentPath = path + dir.getName() + File.separatorChar;
    if (dir.listFiles() != null) {
      for (File file : dir.listFiles()) {
        if (file.isFile() && isJsp(file.getName().toLowerCase())) {
          jspPaths.add(currentPath + file.getName());
        } else if (file.isDirectory()) {
          addDirectoryJSPs(currentPath, file, jspPaths);
        }
      }
    }
  }

  private boolean isJsp(final String name) {
    return name.endsWith(".jsp") || name.endsWith(".jspx");
  }
}
