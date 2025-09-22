package mule4;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.util.MuleSystemProperties;
import org.mule.runtime.core.api.config.MuleProperties;
import org.mule.runtime.module.launcher.DefaultMuleContainer;

/**
 * A Mule test container where it is possible to deploy and undeploy mule applications.
 *
 * <p>This code assumes that the services needed by the mule applications are already present in the
 * mule directory.
 */
public class MuleTestContainer {
  DefaultMuleContainer container;

  public MuleTestContainer(File muleBaseDirectory) throws IOException {
    if (!muleBaseDirectory.exists()) {
      muleBaseDirectory.mkdirs();
    }
    String basePath = muleBaseDirectory.getCanonicalPath();
    // Makes sure that Mule doesn't try to configure its own logging
    System.setProperty(MuleSystemProperties.MULE_SIMPLE_LOG, "true");
    // This is the Mule runtime folder where files are stored
    System.setProperty(MuleProperties.MULE_BASE_DIRECTORY_PROPERTY, basePath);
    System.setProperty(MuleProperties.MULE_HOME_DIRECTORY_PROPERTY, basePath);
    System.setProperty("mule.classloader.container.jpmsModuleLayer", "false");
    // Mule is a bit picky with some directories existing, so let's create them
    for (String dirName : new String[] {"domains/default", "apps"}) {
      File dir = new File(muleBaseDirectory, dirName);
      if (!dir.exists()) {
        dir.mkdirs();
      }
    }
    try {
      this.container = DefaultMuleContainer.class.newInstance();
    } catch (Throwable t) {
      t.printStackTrace();
      try {
        this.container =
            DefaultMuleContainer.class
                .getDeclaredConstructor(String[].class)
                .newInstance((Object) new String[0]);
      } catch (Throwable t2) {
        throw new RuntimeException("Unable to instantiate MuleContainer", t2);
      }
    }
  }

  public void start() throws MuleException {
    container.start(false);
  }

  public void deploy(URI app, Properties appProperties) throws IOException {
    container.getDeploymentService().deploy(app, appProperties);
  }

  public void undeploy(String appName) {
    container.getDeploymentService().undeploy(appName);
  }

  public void stop() throws Exception {
    container.shutdown();
  }
}
