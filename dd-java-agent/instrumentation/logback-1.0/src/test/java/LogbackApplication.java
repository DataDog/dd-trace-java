import java.util.List;
import java.util.Map;

/** Logback test application loaded apart from the logging backend of the test harness. */
public interface LogbackApplication {
  List<Map<String, String>> getContexts();

  void log(String level, String message);

  void put(String key, String value);

  void clear();

  void startAsync();

  Map<String, String> finishAsync() throws InterruptedException;

  void jul();
}
