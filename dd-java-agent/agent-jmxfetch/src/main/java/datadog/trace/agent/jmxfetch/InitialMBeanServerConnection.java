package datadog.trace.agent.jmxfetch;

import java.io.IOException;
import javax.annotation.Nonnull;
import javax.management.MBeanServerConnection;
import org.datadog.jmxfetch.JvmDirectConnection;

public class InitialMBeanServerConnection extends JvmDirectConnection {

  public InitialMBeanServerConnection(@Nonnull final MBeanServerConnection mbs) throws IOException {
    this.mbs = mbs;
  }

  @Override
  protected void createConnection() {
    // already connected
  }
}
