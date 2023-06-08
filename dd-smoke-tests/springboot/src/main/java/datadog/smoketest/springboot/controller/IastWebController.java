package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.TestBean;
import ddtest.client.sources.Hasher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.server.PathParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.servlet.view.UrlBasedViewResolver;

@RestController
public class IastWebController {

  private final Hasher hasher;
  private final Random random;

  public IastWebController() {
    hasher = new Hasher();
    hasher.sha1();
    random = new Random();
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

  @PostMapping("/ssrf")
  public String ssrf(@RequestParam("url") final String url) {
    try {
      final URL target = new URL(url);
      final HttpURLConnection conn = (HttpURLConnection) target.openConnection();
      conn.disconnect();
    } catch (final Exception e) {
    }
    return "Url is: " + url;
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
}
