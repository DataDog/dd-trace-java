package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
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

  private static final String DEFAULT_HTML_ESCAPE_PATTERN = "defaultHtmlEscape";

  private static final String TOMCAT_MANAGER_APPLICATION_PATTERN = "Tomcat Manager Application";

  private static final String LISTINGS_PATTERN = "<param-name>listings</param-name>";

  private static final String SESSION_TIMEOUT_PATTERN = "<session-timeout>";

  private static final String SECURITY_CONSTRAINT_PATTERN = "<security-constraint>";

  private static final String REGEX =
      String.join(
          "|",
          CONTEXT_LOADER_LISTENER_PATTERN,
          DISPATCHER_SERVLET_PATTERN,
          DEFAULT_HTML_ESCAPE_PATTERN,
          TOMCAT_MANAGER_APPLICATION_PATTERN,
          LISTINGS_PATTERN,
          SESSION_TIMEOUT_PATTERN,
          SECURITY_CONSTRAINT_PATTERN);

  private static final Pattern PATTERN = Pattern.compile(REGEX);

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
        case DEFAULT_HTML_ESCAPE_PATTERN:
          defaultHtmlEscapeIndex = matcher.start();
          break;
        case TOMCAT_MANAGER_APPLICATION_PATTERN:
          reportAdminConsoleActive(span);
          break;
        case LISTINGS_PATTERN:
          checkDirectoryListingLeak(webXmlContent, matcher.start(), span);
          break;
        case SESSION_TIMEOUT_PATTERN:
          checkSessionTimeOut(webXmlContent, matcher.start(), span);
          break;
        case SECURITY_CONSTRAINT_PATTERN:
          checkVerbTampering(webXmlContent, matcher.start(), span);
          break;
      }
    }

    if (isSpring && defaultHtmlEscapeIndex != -1) {
      checkDefaultHtmlEscapeInvalid(webXmlContent, span, defaultHtmlEscapeIndex);
    }
  }

  private void checkDefaultHtmlEscapeInvalid(
      String webXmlContent, AgentSpan span, int defaultHtmlEscapeIndex) {
    String value = "false";
    if (defaultHtmlEscapeIndex != -1) {
      int start =
          webXmlContent.indexOf("<param-value>", defaultHtmlEscapeIndex) + "<param-value>".length();
      value = webXmlContent.substring(start, webXmlContent.indexOf("</param-value>", start));
    }
    if (defaultHtmlEscapeIndex == -1 || !Boolean.parseBoolean(value)) {
      reporter.report(
          span,
          new Vulnerability(
              VulnerabilityType.DEFAULT_HTML_ESCAPE_INVALID,
              Location.forSpanAndFileAndLine(span, "web.xml", -1),
              new Evidence("defaultHtmlEscape tag should be set")));
    }
  }

  private void reportAdminConsoleActive(AgentSpan span) {
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.ADMIN_CONSOLE_ACTIVE,
            Location.forSpanAndFileAndLine(span, "web.xml", -1),
            new Evidence("Tomcat Manager Application")));
  }

  private void checkDirectoryListingLeak(
      final String webXmlContent, int index, final AgentSpan span) {
    int valueIndex = webXmlContent.indexOf("<param-value>", index);
    int valueLast = webXmlContent.indexOf("</param-value>", valueIndex);
    String data = webXmlContent.substring(valueIndex, valueLast);
    if (data.trim().toLowerCase().contains("true")) {
      reporter.report(
          span,
          new Vulnerability(
              VulnerabilityType.DIRECTORY_LISTING_LEAK,
              Location.forSpanAndFileAndLine(span, "web.xml", -1),
              new Evidence("Directory listings configured")));
    }
  }

  private void checkSessionTimeOut(final String webXmlContent, int index, final AgentSpan span) {
    Clue clue =
        getFirstClue(webXmlContent.substring(index), "<session-timeout>", "</session-timeout>");
    String innerText = clue.getValue();
    if (innerText != null) {
      innerText = innerText.trim();
      try {
        int timeoutValue = Integer.parseInt(innerText);
        if (timeoutValue > 30 || timeoutValue == -1) {
          reporter.report(
              span,
              new Vulnerability(
                  VulnerabilityType.SESSION_TIMEOUT,
                  Location.forSpanAndFileAndLine(span, "web.xml", clue.getLine()),
                  new Evidence("Found vulnerable timeout value: " + timeoutValue)));
        }
      } catch (NumberFormatException e) {
        // Nothing to do
      }
    }
  }

  private void checkVerbTampering(final String webXmlContent, int index, final AgentSpan span) {
    Clue clue =
        getFirstClue(
            webXmlContent.substring(index), "<security-constraint>", "</security-constraint>");
    if (clue.getValue() != null && !clue.getValue().contains("<http-method>")) {
      reporter.report(
          span,
          new Vulnerability(
              VulnerabilityType.VERB_TAMPERING,
              Location.forSpanAndFileAndLine(span, "web.xml", clue.getLine()),
              new Evidence("http-method not defined in web.xml")));
    }
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
            VulnerabilityType.INSECURE_JSP_LAYOUT,
            Location.forSpanAndFileAndLine(span, "web.xml", -1),
            new Evidence(result)));
  }

  @Nullable
  private String webXmlContent(final String realPath) {
    if (realPath != null) {
      Path path =
          Paths.get(realPath + File.separatorChar + "WEB-INF" + File.separatorChar + "web.xml");
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

  private Clue getFirstClue(final String text, final String startToken, final String endToken) {
    Clue match = new Clue();
    if (text == null) {
      return match;
    }

    BufferedReader br = new BufferedReader(new StringReader(text));

    try {
      int startLine = -1;
      int endLine = -1;
      int currentLine = 0;
      StringBuilder innerText = new StringBuilder();
      String buff;
      while ((buff = br.readLine()) != null) {
        if (startLine == -1 && buff.contains(startToken)) {
          startLine = currentLine;
          int idx = buff.indexOf(startToken);

          int endIdx = buff.indexOf(endToken);
          if (endIdx != -1) {
            innerText.append(buff.substring(idx + startToken.length(), endIdx));
            endLine = currentLine;
            match.setLine(startLine);
            match.setInnerText(innerText.toString());
            return match;
          }
        } else if (endLine == -1 && buff.contains(endToken)) {
          int idx = buff.indexOf(endToken);
          innerText.append(buff.substring(0, idx));
          endLine = currentLine;
          match.setLine(startLine);
          match.setInnerText(innerText.toString());
          return match;
        } else if (startLine != -1 && endLine == -1) {
          innerText.append(buff);
        }
        currentLine++;
      }
    } catch (IOException e) {
      // Ignore
    }
    return match;
  }

  private List<String> findRecursively(final File root) {
    List<String> jspPaths = new ArrayList<>();
    if (root.listFiles() != null) {
      for (File file : root.listFiles()) {
        if (file.isFile() && isJsp(file.getName().toLowerCase())) {
          jspPaths.add(file.getName());
        } else if (file.isDirectory() && !"WEB-INF".equals(file.getName())) {
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

  class Clue {
    @Nullable private String innerText;

    private int line;

    Clue() {}

    @Nullable
    String getValue() {
      return innerText;
    }

    void setInnerText(final String txt) {
      innerText = txt;
    }

    int getLine() {
      return line;
    }

    void setLine(final int line) {
      this.line = line;
    }
  }
}
