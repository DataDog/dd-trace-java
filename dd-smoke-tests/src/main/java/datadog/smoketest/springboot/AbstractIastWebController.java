package datadog.smoketest.springboot;

import ddtest.client.sources.Hasher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import javax.servlet.http.HttpServletRequest;

public abstract class AbstractIastWebController {

  private final Hasher hasher;

  public AbstractIastWebController() {
    hasher = new Hasher();
    hasher.sha1();
  }

  public String greeting() {
    return "Sup Dawg";
  }

  public String weakhash() {
    hasher.md5().digest("Message body".getBytes(StandardCharsets.UTF_8));
    return "Weak Hash page";
  }

  public String asyncWeakhash() {
    final Thread thread = new Thread(hasher::md4);
    thread.start();
    return "Weak Hash page";
  }

  public String getParameter(String param, HttpServletRequest request) {
    return "Param is: " + param;
  }

  public String commandInjectionRuntime(final HttpServletRequest request) {
    withProcess(() -> Runtime.getRuntime().exec(request.getParameter("cmd")));
    return "Command Injection page";
  }

  public String commandInjectionProcessBuilder(final HttpServletRequest request) {
    withProcess(() -> new ProcessBuilder(request.getParameter("cmd")).start());
    return "Command Injection page";
  }

  @SuppressFBWarnings("PT_ABSOLUTE_PATH_TRAVERSAL")
  public String pathTraversalFile(final HttpServletRequest request) {
    new File(request.getParameter("path"));
    return "Path Traversal page";
  }

  @SuppressFBWarnings("PT_ABSOLUTE_PATH_TRAVERSAL")
  public String pathTraversalPaths(final HttpServletRequest request) {
    Paths.get(request.getParameter("path"));
    return "Path Traversal page";
  }

  public String pathTraversalPath(final HttpServletRequest request) {
    new File(System.getProperty("user.dir")).toPath().resolve(request.getParameter("path"));
    return "Path Traversal page";
  }

  public String paramBinding(final TestBean testBean) {
    return "Test bean -> name: " + testBean.getName() + ", value: " + testBean.getValue();
  }

  public String requestHeader(String header) {
    return "Header is: " + header;
  }

  public String pathParam(String param) {
    return "PathParam is: " + param;
  }

  public String jsonRequestBody(TestBean testBean) {
    return "@RequestBody to Test bean -> name: "
        + testBean.getName()
        + ", value: "
        + testBean.getValue();
  }

  protected void withProcess(final Operation<Process> op) {
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

  protected interface Operation<E> {
    E run() throws Throwable;
  }
}
