package datadog.trace.agent.jmxfetch;

import datadog.trace.bootstrap.instrumentation.jmx.MBeanServerRegistry;
import java.io.IOException;
import java.util.Map;
import javax.management.MBeanServer;
import org.datadog.jmxfetch.Connection;
import org.datadog.jmxfetch.ConnectionFactory;
import org.datadog.jmxfetch.DefaultConnectionFactory;
import org.datadog.jmxfetch.JvmDirectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentConnectionFactory implements ConnectionFactory {
  private static final Logger log = LoggerFactory.getLogger(AgentConnectionFactory.class);

  private final ConnectionFactory defaultConnectionFactory = new DefaultConnectionFactory();

  public AgentConnectionFactory() {}

  @Override
  public Connection createConnection(Map<String, Object> connectionParams) throws IOException {
    Object mbeanServerClass = connectionParams.get("mbean_server_class");
    if (mbeanServerClass != null) {
      MBeanServer mbeanServer = MBeanServerRegistry.getServer(mbeanServerClass.toString());
      if (mbeanServer != null) {
        return new InitialMBeanServerConnection(mbeanServer);
      }
      log.warn(
          "Unable to provide a MBean server instance of {}. Falling back to platform default",
          mbeanServerClass);
      return new JvmDirectConnection();
    }
    return defaultConnectionFactory.createConnection(connectionParams);
  }
}
