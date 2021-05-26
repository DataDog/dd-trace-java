package io.sqreen.testapp.imitation.util.exec;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** Execution convenience class. */
public final class ExecUtil {

  /**
   * Executes the specified command.
   *
   * @param params an array containing the command and it's arguments.
   * @return the {@link InputStream} containing the command output (it's STDOUT)
   */
  public static InputStream exec(String[] params) {
    return exec(params, new HashMap<String, String>());
  }

  /**
   * Executes the specified command.
   *
   * @param params an array containing the command and it's arguments.
   * @param env a {@link Map} with environment variables to pass to the command being executed
   * @return the {@link InputStream} containing the command output (it's STDOUT)
   */
  public static InputStream exec(String[] params, Map<String, String> env) {
    try {
      List<String> envp = new LinkedList<String>();
      for (Map.Entry<String, String> entry : env.entrySet()) {
        envp.add(entry.getKey() + "=" + entry.getValue());
      }

      Process p = Runtime.getRuntime().exec(params, envp.toArray(new String[] {}));

      StreamConsumer stderrConsumer = new StreamConsumer(p.getErrorStream(), "STDERR consumer");
      stderrConsumer.start();

      return p.getInputStream();

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ExecUtil() {
    /**/
  }
}
