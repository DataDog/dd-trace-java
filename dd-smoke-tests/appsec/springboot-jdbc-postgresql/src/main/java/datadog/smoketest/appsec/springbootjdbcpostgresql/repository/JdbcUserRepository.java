package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.User;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int save(User user) {
    return jdbcTemplate.update(
        "INSERT INTO users (username, password) VALUES('" +user.getUsername()+ "', '" +user.getPassword()+ "')");
  }

  @Override
  public int update(User user) {
    return jdbcTemplate.update(
        "UPDATE users SET username='" +user.getUsername()+ "', password='" +user.getPassword()+"' WHERE id=" + user.getId());
  }

  @Override
  public User findByUsername(String username) {
    try {
      User user =
          jdbcTemplate.queryForObject(
              "SELECT * FROM users WHERE username=?",
              BeanPropertyRowMapper.newInstance(User.class),
              username);
      return user;
    } catch (IncorrectResultSizeDataAccessException e) {
      return null;
    }
  }

  @Override
  public int deleteById(Long id) {
    return jdbcTemplate.update("DELETE FROM users WHERE id=?", id);
  }
}
