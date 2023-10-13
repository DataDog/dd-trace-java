package datadog.smoketest.springboot.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;

public class SpannerTask {

  @Async
  public CompletableFuture<ResultSet> spannerResultSet() {
    return getSpannerResultSet();
  }

  public CompletableFuture<ResultSet> getSpannerResultSet() {
    SpannerOptions options = SpannerOptions.newBuilder().setProjectId("foo").build();
    Spanner spanner = options.getService();
    DatabaseId db = DatabaseId.of(options.getProjectId(), "", "");
    DatabaseClient dbClient = spanner.getDatabaseClient(db);

    Statement sql =
        Statement.newBuilder(
                "SELECT table_name FROM information_schema.tables WHERE table_catalog = '' and table_schema = ''")
            .build();

    return CompletableFuture.completedFuture(dbClient.singleUse().executeQuery(sql));
  }
}
