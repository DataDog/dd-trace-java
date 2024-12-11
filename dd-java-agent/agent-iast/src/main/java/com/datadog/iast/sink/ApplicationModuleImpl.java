package com.datadog.iast.sink;

import static com.datadog.iast.util.StringUtils.endsWithIgnoreCase;
import static com.datadog.iast.util.StringUtils.substringTrim;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

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
  private static final String LISTINGS_PATTERN = "<param-name>listings</param-name>";
  private static final String JETTY_LISTINGS_PATTERN = "<param-name>dirAllowed</param-name>";
  private static final String WEBLOGIC_LISTING_PATTERN =
      "<index-directory-enabled>true</index-directory-enabled>";
  private static final String WEBSPHERE_XMI_LISTING_PATTERN = "directoryBrowsingEnabled=\"true\"";
  private static final String WEBSPHERE_XML_LISTING_PATTERN =
      "<enable-directory-browsing value=\"true\"/>";
  private static final String SESSION_TIMEOUT_START_TAG = "<session-timeout>";
  private static final String SESSION_TIMEOUT_END_TAG = "</session-timeout>";
  private static final String SECURITY_CONSTRAINT_START_TAG = "<security-constraint>";
  private static final String SECURITY_CONSTRAINT_END_TAG = "</security-constraint>";
  public static final String PARAM_VALUE_START_TAG = "<param-value>";
  public static final String PARAM_VALUE_END_TAG = "</param-value>";
  public static final String DISPLAY_NAME_PATTERN = "<display-name>(.*?)</display-name>";
  static final String TOMCAT_MANAGER_APP = "Tomcat Manager Application";
  static final String TOMCAT_HOST_MANAGER_APP = "Tomcat Host Manager Application";
  static final String TOMCAT_SAMPLES_APP = "Servlet and JSP Examples";
  static final String JETTY_ASYNC_REST_APP = "Async REST Webservice Example";
  static final String JETTY_JAVADOC_APP = "Transparent Proxy WebApp";
  static final String JETTY_JAAS_APP = "JAAS Test";
  static final String JETTY_JNDI_APP = "Test JNDI WebApp";
  static final String JETTY_SPEC_APP = "Test Annotations WebApp";
  static final String JETTY_TEST_APP = "Test WebApp";
  public static final Set<String> ADMIN_CONSOLE_LIST =
      new HashSet<>(Arrays.asList(TOMCAT_MANAGER_APP, TOMCAT_HOST_MANAGER_APP));
  public static final Set<String> DEFAULT_APP_LIST =
      new HashSet<>(
          Arrays.asList(
              TOMCAT_SAMPLES_APP,
              JETTY_ASYNC_REST_APP,
              JETTY_JAVADOC_APP,
              JETTY_JAAS_APP,
              JETTY_JNDI_APP,
              JETTY_SPEC_APP,
              JETTY_TEST_APP));
  public static final String WEB_INF = "WEB-INF";
  public static final String WEB_XML = "web.xml";

  public static final String TOMCAT_USERS_XML = "tomcat-users.xml";
  public static final String TOMCAT_SERVER_XML = "server.xml";
  public static final String WEBLOGIC_XML = "weblogic.xml";
  public static final String IBM_WEB_EXT_XMI = "ibm-web-ext.xmi";
  public static final String IBM_WEB_EXT_XML = "ibm-web-ext.xml";
  static final String SESSION_REWRITING_EVIDENCE_VALUE = "Servlet URL Session Tracking Mode";

  private static final Pattern PATTERN =
      Pattern.compile(
          Stream.of(
                  CONTEXT_LOADER_LISTENER,
                  DISPATCHER_SERVLET,
                  DEFAULT_HTML_ESCAPE,
                  LISTINGS_PATTERN,
                  JETTY_LISTINGS_PATTERN,
                  SESSION_TIMEOUT_START_TAG,
                  SECURITY_CONSTRAINT_START_TAG,
                  DISPLAY_NAME_PATTERN)
              .collect(Collectors.joining("|")));

  private static final Pattern WEBLOGIC_PATTERN =
      Pattern.compile(WEBLOGIC_LISTING_PATTERN, Pattern.CASE_INSENSITIVE);

  private static final Pattern WEBSPHERE_XMI_PATTERN =
      Pattern.compile(WEBSPHERE_XMI_LISTING_PATTERN, Pattern.CASE_INSENSITIVE);

  private static final Pattern WEBSPHERE_XML_PATTERN =
      Pattern.compile(WEBSPHERE_XML_LISTING_PATTERN, Pattern.CASE_INSENSITIVE);

  private static final int NO_LINE = -1;

  public ApplicationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  /**
   * Overhead is not checked here as it's called once per application context
   *
   * @param realPath the real path of the application
   */
  @Override
  public void onRealPath(String serverInfo, final @Nullable String realPath) {
    if (realPath == null) {
      return;
    }
    final Path root = Paths.get(realPath);
    if (!Files.exists(root)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();

    if (serverInfo != null) {
      if (serverInfo.contains("Tomcat")) {
        checkTomcatVulnerabilities(serverInfo, root, span);
      }
    }

    checkInsecureJSPLayout(root, span);
    checkWebXmlVulnerabilities(serverInfo, root, span);
    // WEBLOGIC
    checkWeblogicVulnerabilities(root, span);
    // WEBSPHERE
    checkWebsphereVulnerabilities(root, span);
  }

  /**
   * Overhead is not checked here as it's called once per application context
   *
   * @param sessionTrackingModes the session tracking modes
   */
  @Override
  public void checkSessionTrackingModes(@Nonnull Set<String> sessionTrackingModes) {
    if (!sessionTrackingModes.contains("URL")) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    // No deduplication is needed as same service can have multiple applications
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.SESSION_REWRITING,
            Location.forSpan(span),
            new Evidence(SESSION_REWRITING_EVIDENCE_VALUE)));
  }

  private void checkTomcatVulnerabilities(String serverInfo, @Nonnull final Path path, final AgentSpan span) {
    String tomcatPath = System.getProperty("catalina.home");

    RequestContext reqCtx = span.getRequestContext();
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    TriFunction<RequestContext, String, Map<String, ?>, Flow<Void>> callback =
            cbp.getCallback(EVENTS.configurationData());
    if (reqCtx == null || callback == null) {
      return;
    }

    try {

      // Check for default tomcat applications (Manager, Host Manager, etc)
      Path tomcatWebappsPath = Paths.get(tomcatPath, "webapps");

      Map<String, Object> webapps = new LinkedHashMap<>();
      List<String> appNames = new ArrayList<>();
      webapps.put("webapps", appNames);
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(tomcatWebappsPath, Files::isDirectory)) {
        for (Path p : stream) {
          //System.out.println("Folder: " + p.getFileName());
          appNames.add(p.getFileName().toString());
        }

        Flow<Void> flow = callback.apply(reqCtx, serverInfo, webapps);
      } catch (IOException e) {
        System.err.println("Error reading directory: " + e.getMessage());
      }


      // Check for Weak credentials in tomcat-users.xml
      Path tomcatUsersXmlPath = Paths.get(tomcatPath, "conf", TOMCAT_USERS_XML);
      Map<String, List<Map<String, Object>>> tomcatUsersXmlConfig = getParsedXmlContent(tomcatUsersXmlPath);
      if (tomcatUsersXmlConfig != null) {
        List<Map<String, Object>> userTags = tomcatUsersXmlConfig.get("user");
        if (userTags != null) {
          for (Map<String, Object> userTag : userTags) {
            Flow<Void> flow = callback.apply(reqCtx, serverInfo, userTag);
          }
        }
      }


      // Check server.xml
      Path serverXmlPath = Paths.get(tomcatPath, "conf", TOMCAT_SERVER_XML);
      Map<String, List<Map<String, Object>>> serverXmlConfig = getParsedXmlContent(serverXmlPath);

      if (serverXmlConfig != null) {
        List<Map<String, Object>> valveTags = serverXmlConfig.get("Valve");
        if (valveTags != null) {
          for (Map<String, Object> valveTag : valveTags) {
            Flow<Void> flow = callback.apply(reqCtx, serverInfo, valveTag);
          }
        }
        List<Map<String, Object>> connectorTags = serverXmlConfig.get("Connector");
        if (connectorTags != null) {
          for (Map<String, Object> connectorTag : connectorTags) {
            Map<String, Object> serviceTag = new LinkedHashMap<>();
            serviceTag.put("Connector", connectorTag);
            Flow<Void> flow = callback.apply(reqCtx, serverInfo, serviceTag);
          }
        }
      }

      // Check jvm properties
      Properties properties = System.getProperties();
      Map<String, Object> jvmProperties = new LinkedHashMap<>();
      Map<String, Object> propertiesTag = new LinkedHashMap<>();
      for (String key : properties.stringPropertyNames()) {
        propertiesTag.put(key, properties.getProperty(key));
      }
      jvmProperties.put("Properties", propertiesTag);
      Flow<Void> flow = callback.apply(reqCtx, serverInfo, jvmProperties);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  private void checkWebsphereVulnerabilities(@Nonnull final Path path, final AgentSpan span) {
    checkWebsphereXMLVulnerabilities(path, span);
    checkWebsphereXMIVulnerabilities(path, span);
  }

  private void checkWebsphereXMIVulnerabilities(@Nonnull final Path path, final AgentSpan span) {
    String xmlContent = getXmlContent(path, IBM_WEB_EXT_XMI);
    if (xmlContent == null) {
      return;
    }
    Matcher matcher = WEBSPHERE_XMI_PATTERN.matcher(xmlContent);
    while (matcher.find()) {
      reportDirectoryListingLeak(xmlContent, matcher.start(), span);
    }
  }

  private void checkWebsphereXMLVulnerabilities(@Nonnull final Path path, final AgentSpan span) {
    String xmlContent = getXmlContent(path, IBM_WEB_EXT_XML);
    if (xmlContent == null) {
      return;
    }
    Matcher matcher = WEBSPHERE_XML_PATTERN.matcher(xmlContent);
    while (matcher.find()) {
      reportDirectoryListingLeak(xmlContent, matcher.start(), span);
    }
  }

  private void checkWeblogicVulnerabilities(@Nonnull final Path path, final AgentSpan span) {
    String xmlContent = getXmlContent(path, WEBLOGIC_XML);
    if (xmlContent == null) {
      return;
    }
    Matcher matcher = WEBLOGIC_PATTERN.matcher(xmlContent);
    while (matcher.find()) {
      reportDirectoryListingLeak(xmlContent, matcher.start(), span);
    }
  }

  public static Map<String, List<Map<String, Object>>> parseXmlFromString(String xmlInput) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    try (InputStream xmlStream = new ByteArrayInputStream(xmlInput.getBytes())) {
      XMLStreamReader reader = factory.createXMLStreamReader(xmlStream);
      return parseGroupedElements(reader);
    } catch (IOException e) {
      throw new RuntimeException("Error reading XML input", e);
    }
  }

  private static Map<String, List<Map<String, Object>>> parseGroupedElements(XMLStreamReader reader) throws XMLStreamException {
    Map<String, List<Map<String, Object>>> groupedMap = new LinkedHashMap<>();

    while (reader.hasNext()) {
      int eventType = reader.next();

      switch (eventType) {
        case XMLStreamConstants.START_ELEMENT:
          String currentElement = reader.getLocalName();
          Map<String, String> attributes = getAttributes(reader);

          // Создаем элемент
          Map<String, Object> element = new LinkedHashMap<>(attributes);

          // Добавляем элемент в общую структуру
          groupedMap.computeIfAbsent(currentElement, k -> new ArrayList<>()).add(element);
          break;

        case XMLStreamConstants.END_ELEMENT:
          // Просто выходим из цикла на конец элемента
          break;

        case XMLStreamConstants.CHARACTERS:
          // Игнорируем текст, так как у нас только атрибуты нужны
          break;
      }
    }
    return groupedMap;
  }

  private static Map<String, String> getAttributes(XMLStreamReader reader) {
    Map<String, String> attributes = new LinkedHashMap<>();
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      attributes.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
    }
    return attributes;
  }


//  public static Map<String, Object> parseXmlFromString(String xmlContent) throws Exception {
//    Map<String, Object> result = new LinkedHashMap<>();
//    Deque<Map<String, Object>> stack = new ArrayDeque<>();
//    stack.push(result);
//
//    XMLInputFactory factory = XMLInputFactory.newInstance();
//    XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xmlContent));
//
//    String currentElement = null;
//    while (reader.hasNext()) {
//      int event = reader.next();
//      switch (event) {
//        case XMLStreamConstants.START_ELEMENT:
//          currentElement = reader.getLocalName();
//          Map<String, Object> newElement = new LinkedHashMap<>();
//          stack.peek().merge(currentElement, newElement, (oldValue, newValue) -> {
//            if (oldValue instanceof List) {
//              ((List<Object>) oldValue).add(newValue);
//              return oldValue;
//            } else {
//              List<Object> list = new ArrayList<>();
//              list.add(oldValue);
//              list.add(newValue);
//              return list;
//            }
//          });
//          stack.push(newElement);
//          break;
//        case XMLStreamConstants.CHARACTERS:
//          if (!reader.isWhiteSpace()) {
//            stack.peek().merge(currentElement, reader.getText().trim(), (oldValue, newValue) -> {
//              if (oldValue instanceof List) {
//                ((List<Object>) oldValue).add(newValue);
//                return oldValue;
//              } else {
//                List<Object> list = new ArrayList<>();
//                list.add(oldValue);
//                list.add(newValue);
//                return list;
//              }
//            });
//          }
//          break;
//        case XMLStreamConstants.END_ELEMENT:
//          stack.pop();
//          break;
//      }
//    }
//    return result;
//  }


  private void checkWebXmlVulnerabilities(final String serverInfo, final Path path, final AgentSpan span) {

    String webXmlContent = getXmlContent(path, WEB_XML);
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
        case LISTINGS_PATTERN:
        case JETTY_LISTINGS_PATTERN:
          checkDirectoryListingLeak(webXmlContent, matcher.start(), span);
          break;
        case SESSION_TIMEOUT_START_TAG:
          checkSessionTimeOut(webXmlContent, matcher.start(), span);
          break;
        case SECURITY_CONSTRAINT_START_TAG:
          checkVerbTampering(webXmlContent, matcher.start(), span);
          break;
        default: // DISPLAY NAME MATCH
          String displayName = matcher.group(1);
          if (ADMIN_CONSOLE_LIST.contains(displayName)) {
            reportAdminConsoleActive(span, displayName);
          } else if (DEFAULT_APP_LIST.contains(displayName)) {
            reportDefaultAppDeployed(span, displayName);
          }
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

  private void reportAdminConsoleActive(AgentSpan span, final String evidence) {
    // No deduplication is needed as same service can have multiple applications
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.ADMIN_CONSOLE_ACTIVE,
            Location.forSpan(span),
            new Evidence(evidence)));
  }

  private void reportDefaultAppDeployed(final AgentSpan span, final String evidence) {
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.DEFAULT_APP_DEPLOYED,
            Location.forSpan(span),
            new Evidence(evidence)));
  }

  private void checkDirectoryListingLeak(
      final String webXmlContent, int index, final AgentSpan span) {
    int valueIndex =
        webXmlContent.indexOf(PARAM_VALUE_START_TAG, index) + PARAM_VALUE_START_TAG.length();
    int valueLast = webXmlContent.indexOf(PARAM_VALUE_END_TAG, valueIndex);
    String data = substringTrim(webXmlContent, valueIndex, valueLast);
    if (data.equalsIgnoreCase("true")) {
      reportDirectoryListingLeak(webXmlContent, index, span);
    }
  }

  private void reportDirectoryListingLeak(
      final String webXmlContent, int index, final AgentSpan span) {
    report(
        span,
        VulnerabilityType.DIRECTORY_LISTING_LEAK,
        "Directory listings configured",
        getLine(webXmlContent, index));
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
  private static String readXmlContent(final Path path) {
    if (Files.exists(path)) {
      try {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      } catch (IOException e) {
        LOGGER.debug(SEND_TELEMETRY, "Failed to read {}, encoding issue?", path, e);
      }
    }
    return null;
  }

  @Nullable
  private static Map<String, List<Map<String, Object>>> getParsedXmlContent(final Path path) {
    String xmlContent = readXmlContent(path);
    if (xmlContent == null) {
      return null;
    }
    try {
      return parseXmlFromString(xmlContent);
    } catch (Exception e) {
      LOGGER.debug(SEND_TELEMETRY, "Failed to parse {}", path, e);
    }
    return null;
  }

  @Nullable
  private static String getXmlContent(final Path realPath, final String fileName) {
    Path path = realPath.resolve(WEB_INF).resolve(fileName);
    return readXmlContent(path);
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
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
      final String folder = dir.getFileName().toString();
      if (endsWithIgnoreCase(folder, WEB_INF)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
      final String fileName = file.getFileName().toString();
      if (endsWithIgnoreCase(fileName, ".jsp") || endsWithIgnoreCase(fileName, ".jspx")) {
        folders.add(file.getParent());
        return FileVisitResult.SKIP_SIBLINGS;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
      return FileVisitResult.CONTINUE;
    }
  }
}
