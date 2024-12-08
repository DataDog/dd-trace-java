import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class TestSourceConnector extends SourceConnector {
  private String topic;
  private int intervalMs;

  @Override
  public String version() {
    return "1.0";
  }

  @Override
  public void start(Map<String, String> props) {
    topic = props.get("topic");
    intervalMs = Integer.parseInt(props.getOrDefault("interval.ms", "1000"));
  }

  @Override
  public Class<? extends Task> taskClass() {
    return TestSourceTask.class;
  }

  @Override
  public List<Map<String, String>> taskConfigs(int maxTasks) {
    List<Map<String, String>> configs = new ArrayList<>();
    Map<String, String> config = new HashMap<>();
    config.put("topic", topic);
    config.put("interval.ms", String.valueOf(intervalMs));
    configs.add(config);
    return configs;
  }

  @Override
  public void stop() {
    // Nothing to do here
  }

  @Override
  public ConfigDef config() {
    return null;
  }
}

class TestSourceTask extends SourceTask {
  private String topic;
  private int intervalMs;
  private long lastTimestamp;

  private static final Schema VALUE_SCHEMA = SchemaBuilder.struct()
      .field("message", Schema.STRING_SCHEMA)
      .build();

  @Override
  public String version() {
    return "1.0";
  }

  @Override
  public void start(Map<String, String> props) {
    topic = props.get("topic");
    intervalMs = Integer.parseInt(props.get("interval.ms"));
    lastTimestamp = System.currentTimeMillis();
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    long currentTime = System.currentTimeMillis();
    if (currentTime - lastTimestamp >= intervalMs) {
      lastTimestamp = currentTime;

      // Create a record with a timestamp message
      Struct valueStruct = new Struct(VALUE_SCHEMA)
          .put("message", "Generated at " + currentTime);

      SourceRecord record = new SourceRecord(
          null, null, topic, null, VALUE_SCHEMA, valueStruct
      );

      return List.of(record);
    }

    // Sleep briefly to avoid tight looping
    Thread.sleep(50);
    return null;
  }

  @Override
  public void stop() {
    // Nothing to clean up
  }
}
