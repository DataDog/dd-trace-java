package io.sqreen.testapp.imitation;

import com.google.common.collect.ImmutableMap;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import io.sqreen.testapp.imitation.util.exec.ExecUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import javax.script.*;

/** A various process execution and code evaluation vulnerability imitations. */
public final class VulnerableExecutions {

  /**
   * Executes <code>ping</code> command with the arbitrary additional arguments.
   *
   * @param otherArgs argument to append
   * @return an {@link InputStream} containing the command output
   */
  public static InputStream ping(String otherArgs) {
    return ExecUtil.exec(new String[] {"sh", "-c", "ping -c 1 -w 3 " + otherArgs});
  }

  /**
   * Executes <code>ping</code> command with the specified ip address.
   *
   * @param ipAddress an ip address to use
   * @return an {@link InputStream} containing the command output
   */
  public static InputStream pingNoShell(String ipAddress) {
    return ExecUtil.exec(new String[] {"ping", "-c", "1", "-w", "3", ipAddress});
  }

  private static ScriptEngine groovyEngine = new ScriptEngineManager().getEngineByName("groovy");

  public static void setGroovyEngine(ScriptEngine groovyEngine) {
    VulnerableExecutions.groovyEngine = groovyEngine;
  }

  /**
   * Performs arbitrary groovy code evaluation.
   *
   * @param valueToEval a code to evaluate
   * @param strategy a strategy to choose
   * @param controller reference to the controller/servlet instance
   * @return the evaluation result in string format
   * @throws ScriptException
   */
  public static String eval(final String valueToEval, String strategy, Object controller)
      throws ScriptException {
    Bindings bindings = new SimpleBindings();
    bindings.put("controller", controller);

    Object res = null;
    if ("eval_reader".equals(strategy)) {
      Reader reader = new StringReader(valueToEval);
      res = groovyEngine.eval(reader, bindings);
    } else if ("compile".equals(strategy)) {
      // we're doing it with groovy, but could have used another language like js
      CompiledScript script = ((Compilable) groovyEngine).compile(valueToEval);

      res = script.eval(bindings);
    } else if ("error".equals(strategy)) {
      groovyEngine.eval(new ErrorReader());
    } else { // eval_string
      res = groovyEngine.eval(valueToEval, bindings);
    }

    return (res != null) ? res.toString() : "(null)";
  }

  /**
   * Executes the arbitrary system shell command.
   *
   * @param referer an additional argument to pass
   * @return an {@link InputStream} containing the file contents
   */
  public static InputStream shellShock(final String referer) {
    String[] command = {"bash", "-c", "echo Value of \\$REFERRER: \"$REFERER\""};
    return (referer == null || referer.length() == 0)
        ? ExecUtil.exec(command)
        : ExecUtil.exec(command, ImmutableMap.of("REFERER", referer));
  }

  private static final class ErrorReader extends Reader {

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      throw new IOException("not implemented");
    }

    @Override
    public void close() throws IOException {
      throw new IOException("not implemented");
    }
  }

  @SuppressForbidden
  public static InputStream exec(String command) {
    return ExecUtil.exec(command.split(" "));
  }

  private VulnerableExecutions() {
    /**/
  }
}
