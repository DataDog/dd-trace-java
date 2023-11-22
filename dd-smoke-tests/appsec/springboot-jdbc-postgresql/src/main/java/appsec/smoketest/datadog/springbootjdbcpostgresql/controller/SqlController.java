package appsec.smoketest.datadog.springbootjdbcpostgresql.controller;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SqlController {

  @Autowired private JdbcTemplate jdbcTemplate;

  @GetMapping("/query")
  public String index() {
    return "query";
  }

  @PostMapping("/query")
  public String executeQuery(Model model, @RequestParam String sqlQuery) {
    List<String> columnsNames = new ArrayList<>();
    List<List<Object>> data = new ArrayList<>();
    String error = null;

    try {
      jdbcTemplate.query(
          sqlQuery,
          new ResultSetExtractor<Object>() {
            @Override
            public Object extractData(ResultSet rs) throws SQLException, DataAccessException {
              ResultSetMetaData metaData = rs.getMetaData();
              int columnsCount = metaData.getColumnCount();
              for (int i = 1; i <= columnsCount; i++) {
                columnsNames.add(metaData.getColumnName(i));
              }

              while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columnsCount; i++) {
                  row.add(rs.getObject(i));
                }
                data.add(row);
              }

              return null;
            }
          });
    } catch (Exception e) {
      error = e.getMessage();
    }

    model.addAttribute("columns", columnsNames);
    model.addAttribute("data", data);
    model.addAttribute("sqlQuery", sqlQuery);
    model.addAttribute("error", error);
    return "query";
  }
}
