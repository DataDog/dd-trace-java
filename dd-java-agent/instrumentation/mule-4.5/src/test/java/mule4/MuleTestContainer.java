package mule4;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.commons.io.FileUtils;
import org.mule.runtime.api.util.MuleSystemProperties;
import org.mule.runtime.core.api.config.MuleProperties;
import org.mule.runtime.module.launcher.DefaultMuleContainer;

/** Runs a Mule application in a private runtime home seeded with the suite's extracted services. */
public class MuleTestContainer {
  private final DefaultMuleContainer container;
  private final String appName;

  private MuleTestContainer(File muleBaseDirectory, String appName) throws IOException {
    this.appName = appName;
    String basePath = muleBaseDirectory.getCanonicalPath();
    // Makes sure that Mule doesn't try to configure its own logging
    System.setProperty(MuleSystemProperties.MULE_SIMPLE_LOG, "true");
    // This is the Mule runtime folder where files are stored
    System.setProperty(MuleProperties.MULE_BASE_DIRECTORY_PROPERTY, basePath);
    System.setProperty(MuleProperties.MULE_HOME_DIRECTORY_PROPERTY, basePath);
    System.setProperty("mule.classloader.container.jpmsModuleLayer", "false");
    // Mule is a bit picky with some directories existing, so let's create them
    Path muleBasePath = muleBaseDirectory.toPath();
    Files.createDirectories(muleBasePath.resolve("domains/default"));
    Files.createDirectories(muleBasePath.resolve("apps"));
    container = newContainer();
  }

  private static DefaultMuleContainer newContainer() {
    try {
      return DefaultMuleContainer.class.newInstance();
    } catch (Throwable t) {
      t.printStackTrace();
      try {
        return DefaultMuleContainer.class
            .getDeclaredConstructor(String[].class)
            .newInstance((Object) new String[0]);
      } catch (Throwable t2) {
        throw new RuntimeException("Unable to instantiate MuleContainer", t2);
      }
    }
  }

  public static MuleTestContainer start(
      File home, File template, File application, String appName, Properties appProperties)
      throws Exception {
    FileUtils.copyDirectory(new File(template, "services"), new File(home, "services"));
    FileUtils.copyFileToDirectory(application, home);
    MuleTestContainer mule = new MuleTestContainer(home, appName);
    try {
      mule.container.start(false);
      mule.container
          .getDeploymentService()
          .deploy(new File(home, application.getName()).toURI(), appProperties);
      return mule;
    } catch (Exception | Error failure) {
      try {
        mule.container.shutdown();
      } catch (Exception | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  public void stop() throws Exception {
    try {
      container.getDeploymentService().undeploy(appName);
    } finally {
      container.shutdown();
    }
  }
}
