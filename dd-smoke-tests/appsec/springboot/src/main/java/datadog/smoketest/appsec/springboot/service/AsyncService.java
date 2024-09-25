package datadog.smoketest.appsec.springboot.service;

import java.sql.Connection;
import java.sql.DriverManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AsyncService {

  @Async("taskExecutor")
  public void performAsyncTask(String id) {
    try {
      Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
      conn.createStatement().execute("SELECT 1 FROM DUAL WHERE '1' = '" + id + "'");
    } catch (Exception e) {
      // ignore
    }
  }
}
