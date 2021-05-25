package io.sqreen.testapp.sampleapp

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class PerformanceController {

  @Autowired
  private JdbcTemplate jdbcTemplate

  @RequestMapping('/performance')
  String performance(@ModelAttribute("values") ArrayList<String> values) {
    values.addAll(jdbcTemplate.queryForList("SELECT val FROM thetable", String))
    'performance.ftl'
  }
}
