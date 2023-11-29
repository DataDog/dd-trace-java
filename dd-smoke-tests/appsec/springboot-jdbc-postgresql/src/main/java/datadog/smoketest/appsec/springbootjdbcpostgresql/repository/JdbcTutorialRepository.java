package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.Tutorial;
import java.util.List;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTutorialRepository implements TutorialRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcTutorialRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int save(Tutorial tutorial) {
    return jdbcTemplate.update(
        "INSERT INTO tutorials (title, description, published) VALUES(?,?,?)",
        new Object[] {tutorial.getTitle(), tutorial.getDescription(), tutorial.isPublished()});
  }

  @Override
  public int update(Tutorial tutorial) {
    return jdbcTemplate.update(
        "UPDATE tutorials SET title=?, description=?, published=? WHERE id=?",
        new Object[] {
          tutorial.getTitle(), tutorial.getDescription(), tutorial.isPublished(), tutorial.getId()
        });
  }

  @Override
  public Tutorial findById(Long id) {
    try {
      Tutorial tutorial =
          jdbcTemplate.queryForObject(
              "SELECT * FROM tutorials WHERE id=?",
              BeanPropertyRowMapper.newInstance(Tutorial.class),
              id);

      return tutorial;
    } catch (IncorrectResultSizeDataAccessException e) {
      return null;
    }
  }

  @Override
  public int deleteById(Long id) {
    return jdbcTemplate.update("DELETE FROM tutorials WHERE id=?", id);
  }

  @Override
  public List<Tutorial> findAll() {
    return jdbcTemplate.query(
        "SELECT * from tutorials", BeanPropertyRowMapper.newInstance(Tutorial.class));
  }

  @Override
  public List<Tutorial> findByPublished(boolean published) {
    return jdbcTemplate.query(
        "SELECT * from tutorials WHERE published=?",
        BeanPropertyRowMapper.newInstance(Tutorial.class),
        published);
  }

  @Override
  public List<Tutorial> findByTitleContaining(String title) {
    String q = "SELECT * from tutorials WHERE title ILIKE '%" + title + "%'";

    return jdbcTemplate.query(q, BeanPropertyRowMapper.newInstance(Tutorial.class));
  }

  @Override
  public int deleteAll() {
    return jdbcTemplate.update("DELETE from tutorials");
  }
}
