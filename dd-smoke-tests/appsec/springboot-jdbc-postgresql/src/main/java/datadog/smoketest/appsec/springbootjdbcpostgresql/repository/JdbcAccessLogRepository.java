package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.AccessLog;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcAccessLogRepository implements AccessLogRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcAccessLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }
  @Override
  public int save(AccessLog accessLog) {
    return jdbcTemplate.update(
        "INSERT INTO access_log (user_id, dog_id, access_timestamp) VALUES('"+accessLog.getUserId()+"', '"+accessLog.getDogId()+"', '"+accessLog.getAccessTimestamp()+"')");
  }

  @Override
  public List<AccessLog> findByUserId(Long id) {
    try {
      List<AccessLog> accessLogs =
          jdbcTemplate.query(
              "SELECT * FROM \"AccessLog\" WHERE user_id=?",
              BeanPropertyRowMapper.newInstance(AccessLog.class),
              id);
      return accessLogs;
    } catch (IncorrectResultSizeDataAccessException e) {
      return null;
    }
  }

  @Override
  public List<AccessLog> findByDogId(Long id) {
    try {
      List<AccessLog> accessLogs =
          jdbcTemplate.query(
              "SELECT * FROM access_log WHERE dog_id=?",
              BeanPropertyRowMapper.newInstance(AccessLog.class),
              id);
      return accessLogs;
    } catch (IncorrectResultSizeDataAccessException e) {
      return null;
    }
  }
}
