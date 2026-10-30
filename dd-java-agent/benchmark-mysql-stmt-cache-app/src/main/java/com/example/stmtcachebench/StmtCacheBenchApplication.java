package com.example.stmtcachebench;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class StmtCacheBenchApplication {

  public static void main(String[] args) throws Exception {
    if (Boolean.getBoolean("bench.seed")) {
      // run-bench.sh seeds once with this, before any measured run
      Seeder.main(args);
      return;
    }
    SpringApplication.run(StmtCacheBenchApplication.class, args);
  }

  @Bean
  ApplicationRunner openPool(DataSource dataSource) {
    return args -> {
      try (var c = dataSource.getConnection();
          var ps = c.prepareStatement("SELECT COUNT(*) FROM item");
          var rs = ps.executeQuery()) {
        rs.next();
        if (rs.getLong(1) == 0) {
          throw new IllegalStateException("table item is empty: run with -Dbench.seed=true first");
        }
      }
    };
  }
}
