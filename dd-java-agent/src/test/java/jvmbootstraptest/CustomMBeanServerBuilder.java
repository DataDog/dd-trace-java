package jvmbootstraptest;

import javax.management.MBeanServer;
import javax.management.MBeanServerBuilder;
import javax.management.MBeanServerDelegate;
import javax.management.ObjectName;
import javax.management.StandardMBean;

public class CustomMBeanServerBuilder extends MBeanServerBuilder {
  public interface TestMBean {}

  @Override
  public MBeanServer newMBeanServer(
      final String defaultDomain, final MBeanServer outer, final MBeanServerDelegate delegate) {
    final MBeanServer mBeanServer = super.newMBeanServer(defaultDomain, outer, delegate);
    try {
      mBeanServer.registerMBean(
          new StandardMBean(new TestMBean() {}, TestMBean.class),
          new ObjectName("test:name=custom"));
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    return mBeanServer;
  }
}
