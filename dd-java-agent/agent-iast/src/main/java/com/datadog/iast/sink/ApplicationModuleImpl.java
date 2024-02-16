package com.datadog.iast.sink;

import static com.datadog.iast.util.StringUtils.endsWithIgnoreCase;
import static com.datadog.iast.util.StringUtils.substringTrim;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApplicationModuleImpl extends SinkModuleBase implements ApplicationModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationModule.class);

  /** Bounds for the file visitor depth when trying to locate insecure JSP folders */
  private static final int JSP_MAX_WALK_DEPTH = 32;

  private static final String CONTEXT_LOADER_LISTENER =
      "org.springframework.web.context.ContextLoaderListener";

  private static final String DISPATCHER_SERVLET =
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

  private static final Pattern PATTERN =
      Pattern.compile(
          Stream.of(
                  CONTEXT_LOADER_LISTENER,
                  DISPATCHER_SERVLET,
                  DEFAULT_HTML_ESCAPE,
                  TOMCAT_MANAGER_APPLICATION,
                  LISTINGS_PATTERN,
                  SESSION_TIMEOUT_START_TAG,
                  SECURITY_CONSTRAINT_START_TAG)
              .map(Pattern::quote)
              .collect(Collectors.joining("|")));

  private static final int NO_LINE = -1;

  public ApplicationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRealPath(final @Nullable String realPath) {
    if (realPath == null) {
      return;
    }
    final Path root = Paths.get(realPath);
    if (!Files.exists(root)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInsecureJSPLayout(root, span);
    checkWebXmlVulnerabilities(root, span);
  }

  private void checkWebXmlVulnerabilities(@Nonnull Path path, AgentSpan span) {
    String webXmlContent = webXmlContent(path);
    if (webXmlContent == null) {
      return;
    }

    int defaultHtmlEscapeIndex = -1;
    boolean isSpring = false;

    Matcher matcher = PATTERN.matcher(webXmlContent);
    while (matcher.find()) {
      String match = matcher.group();
      switch (match) {
        case DISPATCHER_SERVLET:
        case CONTEXT_LOADER_LISTENER:
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
      checkDefaultHtmlEscapeInvalid(webXmlContent, defaultHtmlEscapeIndex, span);
    }
  }

  private void checkDefaultHtmlEscapeInvalid(
      @Nonnull String webXmlContent, int defaultHtmlEscapeIndex, AgentSpan span) {
    if (defaultHtmlEscapeIndex != -1) {
      int start =
          webXmlContent.indexOf(PARAM_VALUE_START_TAG, defaultHtmlEscapeIndex)
              + PARAM_VALUE_START_TAG.length();
      String value =
          substringTrim(webXmlContent, start, webXmlContent.indexOf(PARAM_VALUE_END_TAG, start));
      if (!value.equalsIgnoreCase("true")) {
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
    String data = substringTrim(webXmlContent, valueIndex, valueLast);
    if (data.equalsIgnoreCase("true")) {
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
          substringTrim(
              webXmlContent,
              index + SESSION_TIMEOUT_START_TAG.length(),
              webXmlContent.indexOf(SESSION_TIMEOUT_END_TAG, index));
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
        substringTrim(
            webXmlContent,
            index + SECURITY_CONSTRAINT_START_TAG.length(),
            webXmlContent.indexOf(SECURITY_CONSTRAINT_END_TAG, index));
    if (!innerText.contains("<http-method>")) {
      report(
          span,
          VulnerabilityType.VERB_TAMPERING,
          "http-method not defined in web.xml",
          getLine(webXmlContent, index));
    }
  }

  private void report(AgentSpan span, VulnerabilityType type, String value, int line) {
    reporter.report(
        span,
        new Vulnerability(
            type, Location.forSpanAndFileAndLine(span, WEB_XML, line), new Evidence(value)));
  }

  private void checkInsecureJSPLayout(@Nonnull Path path, AgentSpan span) {
    final Collection<Path> jspPaths = findInsecureJspPaths(path);
    if (jspPaths.isEmpty()) {
      return;
    }
    String result =
        jspPaths.stream()
            .map(jspFolder -> relativize(path, jspFolder))
            .collect(Collectors.joining(System.lineSeparator()));
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.INSECURE_JSP_LAYOUT, Location.forSpan(span), new Evidence(result)));
  }

  private static int getLine(String webXmlContent, int index) {
    int line = 1;
    int limit = Math.min(index, webXmlContent.length());
    for (int i = limit; i > 0; i--) {
      if (webXmlContent.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  @Nullable
  private static String webXmlContent(final Path realPath) {
    Path path = realPath.resolve(WEB_INF).resolve(WEB_XML);
    if (Files.exists(path)) {
      try {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      } catch (IOException e) {
        LOGGER.debug(SEND_TELEMETRY, "Failed to read {}, encoding issue?", path, e);
      }
    }
    return null;
  }

  private static Collection<Path> findInsecureJspPaths(final Path root) {
    try {
      final InsecureJspFolderVisitor visitor = new InsecureJspFolderVisitor();
      // TODO is it OK to ignore symlinks here?
      Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), JSP_MAX_WALK_DEPTH, visitor);
      return visitor.folders;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String relativize(final Path root, final Path path) {
    final String relative = root.relativize(path).toString();
    if (relative.isEmpty()) {
      return File.separator;
    }
    if (relative.charAt(0) == File.separatorChar) {
      return relative;
    }
    return File.separatorChar + relative;
  }

  private static class InsecureJspFolderVisitor implements FileVisitor<Path> {
    private final Set<Path> folders = new HashSet<>();

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
        throws IOException {
      final String folder = dir.getFileName().toString();
      if (endsWithIgnoreCase(folder, WEB_INF)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
        throws IOException {
      final String fileName = file.getFileName().toString();
      if (endsWithIgnoreCase(fileName, ".jsp") || endsWithIgnoreCase(fileName, ".jspx")) {
        folders.add(file.getParent());
        return FileVisitResult.SKIP_SIBLINGS;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException exc)
        throws IOException {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
        throws IOException {
      return FileVisitResult.CONTINUE;
    }
  }
}
