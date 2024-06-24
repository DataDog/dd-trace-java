package datadog.trace.agent.jmxfetch;

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
  private static final Logger LOGGER = LoggerFactory.getLogger(AgentConnectionFactory.class);

  private final ConnectionFactory defaultConnectionFactory = new DefaultConnectionFactory();
  private final Map<String, MBeanServer> instanceStore;

  public AgentConnectionFactory(Map<String, MBeanServer> instanceStore) {
    this.instanceStore = instanceStore;
  }

  @Override
  public Connection createConnection(Map<String, Object> map) throws IOException {
    Object mbeanServerClass = map.get("mbean_server_class");
    if (mbeanServerClass != null) {
      MBeanServer mBeanServer = instanceStore.get(mbeanServerClass.toString());
      if (mBeanServer != null) {
        return new InitialMBeanServerConnection(mBeanServer);
      }
      LOGGER.warn(
          "Unable to provide a MBean server instance of {}. Falling back to platform default",
          mbeanServerClass);
      return new JvmDirectConnection();
    }
    return defaultConnectionFactory.createConnection(map);
  }
}
