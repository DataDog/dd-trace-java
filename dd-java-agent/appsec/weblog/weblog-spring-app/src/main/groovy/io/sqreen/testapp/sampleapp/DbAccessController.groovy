package io.sqreen.testapp.sampleapp

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/db')
class DbAccessController {

  @Autowired
  private JdbcTemplate jdbcTemplate

  @RequestMapping(produces = 'text/plain')
  String index() {
    def list = jdbcTemplate.queryForList("SELECT val FROM thetable", String)
    list.join("\n")
  }

  @RequestMapping(value = "/add", produces = 'text/plain')
  String addValue(@RequestParam String value) {
    int affected = jdbcTemplate.update("INSERT INTO thetable(val) VALUES('$value')".toString())
    "Succcess: $affected rows affected"
  }

  @RequestMapping(value = "/sqlSyntaxError", produces = 'text/plain')
  String invalidSql(@RequestParam String value) {
    jdbcTemplate.queryForList("SELECT * FROM '$value", String)
  }
}
