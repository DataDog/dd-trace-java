package datadog.smoketest.springboot.controller;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import datadog.communication.util.IOUtils;
import datadog.smoketest.springboot.TestBean;
import datadog.smoketest.springboot.controller.mock.JakartaMockTransport;
import ddtest.client.sources.Hasher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.websocket.server.PathParam;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.servlet.view.UrlBasedViewResolver;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.yaml.snakeyaml.Yaml;

@RestController
public class IastWebController {

  private final Resource xml;
  private final Hasher hasher;
  private final Random random;

  public IastWebController(@Value("classpath:xpathi.xml") final Resource resource) {
    hasher = new Hasher();
    hasher.sha1();
    random = new Random();
    xml = resource;
  }

  @RequestMapping("/greeting")
  public String greeting() {
    return "Sup Dawg";
  }

  @RequestMapping("/weakhash")
  public String weakhash() {
    hasher.md5().digest("Message body".getBytes(StandardCharsets.UTF_8));
    return "Weak Hash page";
  }

  @RequestMapping("/weak_key_generator")
  public String weakKeyGenerator() {
    hasher.generateKey();
    return "Weak Key generator page";
  }

  @RequestMapping("/weak_key_generator_with_provider")
  public String weakKeyGeneratorWithProvider() {
    hasher.generateKeyWithProvider();
    return "Weak Key generator page";
  }

  @GetMapping("/insecure_cookie")
  public String insecureCookie(HttpServletResponse response) {
    Cookie cookie = new Cookie("user-id", "7");
    response.addCookie(cookie);
    response.setStatus(HttpStatus.OK.value());
    return "Insecure cookie page";
  }

  @GetMapping("/secure_cookie")
  public String secureCookie(HttpServletResponse response) {
    Cookie cookie = new Cookie("user-id", "7");
    cookie.setSecure(true);
    response.addCookie(cookie);
    response.setStatus(HttpStatus.OK.value());
    return "Insecure cookie page";
  }

  @GetMapping("/insecure_cookie_from_header")
  public String insecureCookieFromHeader(HttpServletResponse response) {
    HttpCookie cookie = new HttpCookie("user-id", "7");

    response.addHeader("Set-Cookie", cookie.toString());
    response.setStatus(HttpStatus.OK.value());
    return "Insecure cookie page";
  }

  @GetMapping("/unvalidated_redirect_from_header")
  public String unvalidatedRedirectFromHeader(
      @RequestParam String param, HttpServletResponse response) {
    response.addHeader("Location", param);
    response.setStatus(HttpStatus.FOUND.value());
    return "Unvalidated redirect";
  }

  @GetMapping("/unvalidated_redirect_from_send_redirect")
  public String unvalidatedRedirectFromSendRedirect(
      @RequestParam String param, HttpServletResponse response) throws IOException {
    response.sendRedirect(param);
    return "Unvalidated redirect";
  }

  @GetMapping("/unvalidated_redirect_from_forward")
  public String unvalidatedRedirectFromForward(
      @RequestParam String param, HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    request.getRequestDispatcher(param).forward(request, response);
    return "Unvalidated redirect";
  }

  @GetMapping("/unvalidated_redirect_from_redirect_view")
  public RedirectView unvalidatedRedirectFromRedirectView(
      @RequestParam String param, HttpServletResponse response) {
    return new RedirectView(param);
  }

  @GetMapping("/unvalidated_redirect_from_model_and_view")
  public ModelAndView unvalidatedRedirectFromModelAndView(
      @RequestParam String param, HttpServletResponse response) {
    return new ModelAndView(UrlBasedViewResolver.REDIRECT_URL_PREFIX + param);
  }

  @GetMapping("/unvalidated_redirect_forward_from_model_and_view")
  public ModelAndView unvalidatedRedirectForwardFromModelAndView(
      @RequestParam String param, HttpServletResponse response) {
    return new ModelAndView(UrlBasedViewResolver.FORWARD_URL_PREFIX + param);
  }

  @RequestMapping("/async_weakhash")
  public String asyncWeakhash() {
    final Thread thread = new Thread(hasher::md4);
    thread.start();
    return "Weak Hash page";
  }

  @RequestMapping("/getparameter")
  public String getParameter(@RequestParam String param, HttpServletRequest request) {
    return "Param is: " + param;
  }

  @GetMapping("/cmdi/runtime")
  public String commandInjectionRuntime(final HttpServletRequest request) {
    withProcess(() -> Runtime.getRuntime().exec(request.getParameter("cmd")));
    return "Command Injection page";
  }

  @GetMapping("/cmdi/process_builder")
  public String commandInjectionProcessBuilder(final HttpServletRequest request) {
    withProcess(() -> new ProcessBuilder(request.getParameter("cmd")).start());
    return "Command Injection page";
  }

  @SuppressFBWarnings("PT_ABSOLUTE_PATH_TRAVERSAL")
  @GetMapping("/path_traversal/file")
  public String pathTraversalFile(final HttpServletRequest request) {
    new File(request.getParameter("path"));
    return "Path Traversal page";
  }

  @SuppressFBWarnings("PT_ABSOLUTE_PATH_TRAVERSAL")
  @GetMapping("/path_traversal/paths")
  public String pathTraversalPaths(final HttpServletRequest request) {
    Paths.get(request.getParameter("path"));
    return "Path Traversal page";
  }

  @GetMapping("/path_traversal/path")
  public String pathTraversalPath(final HttpServletRequest request) {
    new File(System.getProperty("user.dir")).toPath().resolve(request.getParameter("path"));
    return "Path Traversal page";
  }

  @GetMapping("/param_binding/test")
  public String paramBinding(final TestBean testBean) {
    return "Test bean -> name: " + testBean.getName() + ", value: " + testBean.getValue();
  }

  @GetMapping("/request_header/test")
  public String requestHeader(@RequestHeader("test-header") String header) {
    return "Header is: " + header;
  }

  @GetMapping("/path_param")
  public String pathParam(@PathParam("param") String param) {
    return "PathParam is: " + param;
  }

  @GetMapping("/matrix/{var1}/{var2}")
  public String matrixAndPathVariables(
      @PathVariable String var1,
      @MatrixVariable(pathVar = "var1") Map<String, String> m1,
      @PathVariable String var2,
      @MatrixVariable(pathVar = "var2") Map<String, String> m2) {
    return "{var1=" + var1 + ", m1=" + m1 + ", var2=" + var2 + ", m2=" + m2 + "}";
  }

  @PostMapping("/request_body/test")
  public String jsonRequestBody(@RequestBody TestBean testBean) {
    return "@RequestBody to Test bean -> name: "
        + testBean.getName()
        + ", value: "
        + testBean.getValue();
  }

  @GetMapping("/query_string")
  public String queryString(final HttpServletRequest request) {
    return "QueryString is: " + request.getQueryString();
  }

  @GetMapping("/cookie")
  public String cookie(final HttpServletRequest request) {
    final Cookie cookie = request.getCookies()[0];
    return "Cookie is: " + cookie.getName() + "=" + cookie.getValue();
  }

  @GetMapping("/jwt")
  public String jwt(Principal userPrincipal) {
    return "ok User Principal name: " + userPrincipal.getName();
  }

  @GetMapping("/weak_randomness")
  public String weak_randomness(@RequestParam("mode") final Class<?> mode) {
    final double result;
    if (mode == ThreadLocalRandom.class) {
      result = ThreadLocalRandom.current().nextDouble();
    } else if (mode == Math.class) {
      result = Math.random();
    } else {
      result = random.nextDouble();
    }
    return "Random : " + result;
  }

  @GetMapping("/xpathi/compile")
  public String xpathInjectionCompile(final HttpServletRequest request)
      throws XPathExpressionException {
    XPathFactory.newInstance().newXPath().compile(request.getParameter("expression"));
    return "XPath Injection page";
  }

  @GetMapping("/xpathi/evaluate")
  public String xpathInjectionEvaluate(final HttpServletRequest request)
      throws XPathExpressionException, ParserConfigurationException, IOException, SAXException {
    DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document doc = b.parse(xml.getInputStream());
    String expression = request.getParameter("expression");
    XPath xPath = XPathFactory.newInstance().newXPath();
    xPath.evaluate(expression, doc.getDocumentElement(), XPathConstants.NODESET);
    return "XPath Injection page";
  }

  @GetMapping("/trust_boundary_violation")
  public String trustBoundaryViolation(final HttpServletRequest request) {
    String paramValue = request.getParameter("paramValue");
    request.getSession().setAttribute("name", paramValue);
    return "Trust Boundary violation page";
  }

  @GetMapping("/trust_boundary_violation_for_cookie")
  public String trustBoundaryViolationForCookie(final HttpServletRequest request)
      throws UnsupportedEncodingException {

    for (Cookie theCookie : request.getCookies()) {
      if (theCookie.getName().equals("https%3A%2F%2Fuser-id2")) {
        String value = java.net.URLDecoder.decode(theCookie.getValue(), "UTF-8");
        request.getSession().putValue(value, "88888");
      }
    }
    return "Trust Boundary violation with cookie page";
  }

  @GetMapping(value = "/hstsmissing", produces = "text/html")
  public String hstsHeaderMissing(HttpServletResponse response) {
    response.setStatus(HttpStatus.OK.value());
    return "ok";
  }

  @GetMapping(value = "/xcontenttypeoptionsmissing", produces = "text/html")
  public String xContentTypeOptionsMissing(HttpServletResponse response) {
    response.addHeader("X-Content-Type-Options", "dosniff");
    response.setStatus(HttpStatus.OK.value());
    return "ok";
  }

  @GetMapping(value = "/insecureAuthProtocol")
  public String insecureAuthProtocol(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    return authorization;
  }

  @PostMapping("/multipart")
  public String handleFileUpload(
      @RequestParam("theFile") MultipartFile file, @RequestParam("param1") String param1) {
    String fileContent = "NO_FILE";
    try {
      fileContent = Arrays.toString(file.getBytes());
      file.getOriginalFilename();
    } catch (IOException e) {
    }
    return "fileName: " + file.getName();
  }

  @PostMapping("/jakartaMailHtmlVulnerability")
  public String jakartaMailHtmlVulnerability(HttpServletRequest request)
      throws jakarta.mail.MessagingException {
    jakarta.mail.Session session = jakarta.mail.Session.getDefaultInstance(new Properties());
    jakarta.mail.Provider provider =
        new jakarta.mail.Provider(
            jakarta.mail.Provider.Type.TRANSPORT,
            "smtp",
            JakartaMockTransport.class.getName(),
            "MockTransport",
            "1.0");
    session.setProvider(provider);
    boolean sanitize =
        StringUtils.isNotEmpty(request.getParameter("sanitize"))
            && request.getParameter("sanitize").equalsIgnoreCase("true");
    jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(session);
    if (request.getParameter("messageText") != null) {
      message.setText(
          sanitize
              ? StringEscapeUtils.escapeHtml4(request.getParameter("messageText"))
              : request.getParameter("messageText"),
          "utf-8",
          "html");
    } else {
      jakarta.mail.Multipart content = new jakarta.mail.internet.MimeMultipart();
      content.addBodyPart(new jakarta.mail.internet.MimeBodyPart());
      content
          .getBodyPart(0)
          .setContent(
              sanitize
                  ? StringEscapeUtils.escapeHtml4(request.getParameter("messageContent"))
                  : request.getParameter("messageContent"),
              "text/html");
      message.setContent(content, "multipart/*");
    }
    message.setRecipients(jakarta.mail.Message.RecipientType.TO, "abc@datadoghq.com");
    jakarta.mail.Transport.send(message);
    return "ok";
  }

  @GetMapping(value = "/xcontenttypeoptionsecure", produces = "text/html")
  public String xContentTypeOptionsSecure(HttpServletResponse response) {
    response.addHeader("X-Content-Type-Options", "nosniff");
    response.setStatus(HttpStatus.OK.value());
    return "ok";
  }

  @GetMapping("/getrequesturi")
  String pathInfo(HttpServletRequest request) {
    String pathInfo = request.getRequestURI();
    return String.format("Request.getRequestURI returns %s", pathInfo);
  }

  @GetMapping("/getrequesturl")
  String requestURL(HttpServletRequest request) {
    StringBuffer requestURL = request.getRequestURL();
    return String.format("Request.getRequestURL returns %s", requestURL);
  }

  @PostMapping("/gson_deserialization")
  String gson(@RequestParam("json") String json) {
    Gson gson = new Gson();
    TestBean testBean = gson.fromJson(json, TestBean.class);
    return "Test bean -> name: " + testBean.getName() + ", value: " + testBean.getValue();
  }

  @PostMapping(value = "/gson_json_parser_deserialization", consumes = MediaType.TEXT_PLAIN_VALUE)
  String gsonJsonParser(@RequestBody String json) {
    JsonParser.parseReader(new StringReader(json));
    return "Ok";
  }

  @GetMapping("/header_injection")
  public String headerInjection(@RequestParam("param") String param, HttpServletResponse response) {
    response.addHeader("X-Test-Header", param);
    return "Ok";
  }

  @GetMapping("/header_injection_exclusion")
  public String headerInjectionExclusion(
      @RequestParam("param") String param, HttpServletResponse response) {
    response.addHeader("Sec-WebSocket-Location", param);
    return "Ok";
  }

  @GetMapping("/header_injection_redaction")
  public String headerInjectionRedaction(
      @RequestParam("param") String param, HttpServletResponse response) {
    response.addHeader("X-Test-Header", param);
    return "Ok";
  }

  @PostMapping("/untrusted_deserialization")
  public String untrustedDeserialization(HttpServletRequest request) throws IOException {
    final ObjectInputStream ois = new ObjectInputStream(request.getInputStream());
    ois.close();
    return "OK";
  }

  @PostMapping("/untrusted_deserialization/multipart")
  public String untrustedDeserializationMultipart(@RequestParam("file") MultipartFile file)
      throws IOException {
    final ObjectInputStream ois = new ObjectInputStream(file.getInputStream());
    ois.close();
    return "OK";
  }

  @PostMapping("/untrusted_deserialization/part")
  public String untrustedDeserializationParts(HttpServletRequest request)
      throws IOException, ServletException {
    List<Part> parts = (List<Part>) request.getParts();
    final ObjectInputStream ois = new ObjectInputStream(parts.get(0).getInputStream());
    ois.close();
    return "OK";
  }

  @GetMapping("/untrusted_deserialization/snakeyaml")
  public String untrustedDeserializationSnakeYaml(@RequestParam("yaml") String param) {
    new Yaml().load(param);
    return "OK";
  }

  @GetMapping("/test_custom_string_reader")
  public String testCustomStringReader(@RequestParam("param") String param) throws IOException {
    return String.join("", IOUtils.readLines(new CustomStringReader(param)));
  }

  private void withProcess(final Operation<Process> op) {
    Process process = null;
    try {
      process = op.run();
    } catch (final Throwable e) {
      // ignore it
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private interface Operation<E> {
    E run() throws Throwable;
  }

  public static class CustomStringReader extends StringReader {

    public CustomStringReader(String s) {
      super(
          "Super "
              + s
              + (new StringReader(
                  "New_1" + new StringReader("New_2" + new StringReader("New_3" + s)))));
    }
  }
}
