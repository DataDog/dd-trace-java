package datadog.smoketest.appsec.springbootjdbcpostgresql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringbootJdbcApplication {

  public static void main(String[] args) {

    //    try {
    //
    //      //String sql = "update tutorials set description = 'Tut#3 Description1' where id = 1";
    //      //String sql = "insert into tutorials (description, published, title) values ('the
    // decsription', true, 'the title')";
    //      //String sql = "insert into tutorials (title) values ('zzz')";
    //      String sql = "insert into tutorials (title) values ('the title' + '1')";
    //      //TablesNamesFinder.findTables()
    //      Map<String, Map<String, Object>> resultStructure = SqlDataExtractor.extractData(sql);
    //      resultStructure.toString();
    //
    //    } catch (JSQLParserException e) {
    //      throw new RuntimeException(e);
    //    }

    SpringApplication.run(SpringbootJdbcApplication.class, args);
  }
}
