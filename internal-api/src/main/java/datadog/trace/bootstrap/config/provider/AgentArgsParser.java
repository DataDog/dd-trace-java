package datadog.trace.bootstrap.config.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentArgsParser {

  private static final Logger log = LoggerFactory.getLogger(AgentArgsParser.class);

  /**
   * Parses agent arguments. Key-value pairs should be separated with ',' character. Key and value
   * within a pair should be separated with '=' character. For simplicity quoting is not supported
   *
   * @param agentArgs in form "key1=value1,key2=value2,..."
   * @return parsed arguments
   */
  public static Map<String, String> parseAgentArgs(String agentArgs) {
    if (agentArgs == null || agentArgs.isEmpty()) {
      return null;
    }
    try {
      Map<String, String> args = new HashMap<>();

      Scanner scanner = new Scanner(agentArgs);
      scanner.useDelimiter(",");
      while (scanner.hasNext()) {
        String arg = scanner.next();
        int idx = arg.indexOf('=');
        String key = arg.substring(0, idx);
        String value = arg.substring(idx + 1);
        args.put(key, value);
      }
      return args;

    } catch (Exception ex) {
      log.error("Error parsing agent args: {}", agentArgs, ex);
      return null;
    }
  }
}
