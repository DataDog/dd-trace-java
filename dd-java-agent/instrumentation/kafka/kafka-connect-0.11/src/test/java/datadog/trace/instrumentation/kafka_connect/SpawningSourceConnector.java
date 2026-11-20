package datadog.trace.instrumentation.kafka_connect;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;

/**
 * Source connector whose task starts its own thread that produces to Kafka, like a sink's commit
 * coordinator thread.
 */
public class SpawningSourceConnector extends SourceConnector {
  private Map<String, String> props;

  @Override
  public String version() {
    return "test";
  }

  @Override
  public void start(Map<String, String> props) {
    this.props = props;
  }

  @Override
  public Class<? extends Task> taskClass() {
    return SpawningSourceTask.class;
  }

  @Override
  public List<Map<String, String>> taskConfigs(int maxTasks) {
    return Collections.singletonList(props);
  }

  @Override
  public void stop() {}

  @Override
  public ConfigDef config() {
    return new ConfigDef();
  }
}
