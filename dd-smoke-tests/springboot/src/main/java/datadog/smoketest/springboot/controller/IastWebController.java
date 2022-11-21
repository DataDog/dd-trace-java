package datadog.smoketest.springboot.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IastWebController {
  @RequestMapping("/greeting")
  public String greeting() {
    return "Sup Dawg";
  }

  @RequestMapping("/weakhash")
  public String weakhash() {
    try {
      MessageDigest.getInstance("MD5").digest("Message body".getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      return "Error: " + e.toString();
    }
    return "Weak Hash page";
  }

  @RequestMapping("/getparameter")
  public String getParameter(@RequestParam String param, HttpServletRequest request) {
    // StringWriter sw = new StringWriter();
    // PrintWriter pw = new PrintWriter(sw);
    // new Throwable().printStackTrace(pw);
    // return sw.toString();
    // TestSuite testSuite = new TestSuite(new HttpServletRequestWrapper(request));
    // testSuite.getParameterMap();
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
