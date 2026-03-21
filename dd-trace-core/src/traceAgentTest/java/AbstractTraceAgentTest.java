import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.TracerConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;

public abstract class AbstractTraceAgentTest {

  private static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig";
  private static final String CONFIG = "datadog.trace.api.Config";

  private static Field instConfigInstanceField;
  private static Constructor<?> instConfigConstructor;
  private static Field configInstanceField;
  private static Constructor<?> configConstructor;
  private static boolean configModifiable = false;

  protected static GenericContainer<?> agentContainer;

  private Properties originalSystemProperties;

  static {
    makeConfigInstanceModifiable();
  }

  private static synchronized void makeConfigInstanceModifiable() {
    if (configModifiable) {
      return;
    }
    try {
      Class<?> instConfigClass = Class.forName(INST_CONFIG);
      instConfigInstanceField = instConfigClass.getDeclaredField("INSTANCE");
      instConfigConstructor = instConfigClass.getDeclaredConstructor();
      instConfigConstructor.setAccessible(true);

      Class<?> configClass = Class.forName(CONFIG);
      configInstanceField = configClass.getDeclaredField("INSTANCE");
      configConstructor = configClass.getDeclaredConstructor();
      configConstructor.setAccessible(true);

      configModifiable = true;
    } catch (Exception e) {
      System.err.println("Config will not be modifiable: " + e.getMessage());
    }
  }

  @BeforeAll
  static void setupAgentContainer() {
    /*
    CI will provide us with agent container running along side our build.
    When building locally, however, we need to take matters into our own hands
    and we use 'testcontainers' for this.
    */
    if (!"true".equals(System.getenv("CI"))) {
      agentContainer =
          new GenericContainer<>("datadog/agent:7.40.1")
              .withEnv("DD_APM_ENABLED", "true")
              .withEnv("DD_BIND_HOST", "0.0.0.0")
              .withEnv("DD_API_KEY", "invalid_key_but_this_is_fine")
              .withEnv("DD_HOSTNAME", "doesnotexist")
              .withEnv("DD_LOGS_STDOUT", "yes")
              .withExposedPorts(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
              .withStartupTimeout(Duration.ofSeconds(120))
              // Apparently we need to sleep for a bit so agent's response `{"service:,env:":1}` in
              // rate_by_service.
              // This is clearly a race-condition and maybe we should avoid verifying complete
              // response
              .withStartupCheckStrategy(
                  new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)));
      agentContainer.start();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    saveProperties();
    injectSysConfig(TracerConfig.AGENT_HOST, getAgentContainerHost());
    injectSysConfig(TracerConfig.TRACE_AGENT_PORT, getAgentContainerPort());
  }

  @AfterAll
  static void cleanupAgentContainer() {
    if (agentContainer != null) {
      agentContainer.stop();
    }
  }

  protected String getAgentContainerHost() {
    if (agentContainer != null) {
      return agentContainer.getHost();
    }
    return System.getenv("CI_AGENT_HOST");
  }

  protected String getAgentContainerPort() {
    if (agentContainer != null) {
      return String.valueOf(agentContainer.getMappedPort(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT));
    }
    return String.valueOf(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT);
  }

  private void saveProperties() {
    originalSystemProperties = new Properties();
    originalSystemProperties.putAll(System.getProperties());
  }

  protected void restoreProperties() {
    if (originalSystemProperties != null) {
      Properties copy = new Properties();
      copy.putAll(originalSystemProperties);
      System.setProperties(copy);
    }
  }

  protected void injectSysConfig(String name, String value) {
    String prefixedName = name.startsWith("dd.") ? name : "dd." + name;
    System.setProperty(prefixedName, value);
    rebuildConfig();
  }

  protected synchronized void rebuildConfig() {
    if (!configModifiable) {
      return;
    }
    try {
      Object newInstConfig = instConfigConstructor.newInstance();
      instConfigInstanceField.set(null, newInstConfig);
      Object newConfig = configConstructor.newInstance();
      configInstanceField.set(null, newConfig);
    } catch (Exception e) {
      throw new RuntimeException("Failed to rebuild config", e);
    }
  }
}
