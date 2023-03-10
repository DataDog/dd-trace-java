package datadog.trace.bootstrap.config.provider;

import java.util.Properties;

/**
 * A holder for config values passed via agent arguments: {@code
 * -javaagent:.../dd-java-agent.jar=dd.key1=value1,dd.key2=value2}
 *
 * <p>Argument names should be the same as config system properties' names
 */
public final class AgentArgsConfigSource extends ConfigProvider.Source {

  /* agent args source is its own class,
  since some properties are filtered by source class
  (see datadog.trace.bootstrap.config.provider.ConfigProvider.getStringExcludingSources) */

  private static volatile Properties AGENT_ARGS;

  /**
   * This method is called by the agent bootstrap logic to inject the arguments. The invocation is
   * done using reflection
   *
   * @param agentArgs Parsed agent arguments
   */
  public static void setAgentArgs(Properties agentArgs) {
    AGENT_ARGS = agentArgs != null ? (Properties) agentArgs.clone() : null;
  }

  public static boolean isEmpty() {
    return AGENT_ARGS == null || AGENT_ARGS.isEmpty();
  }

  private final ConfigProvider.Source delegate;

  public AgentArgsConfigSource() {
    delegate = new PropertiesConfigSource(AGENT_ARGS, true);
  }

  @Override
  protected String get(String key) {
    return delegate.get(key);
  }
}
