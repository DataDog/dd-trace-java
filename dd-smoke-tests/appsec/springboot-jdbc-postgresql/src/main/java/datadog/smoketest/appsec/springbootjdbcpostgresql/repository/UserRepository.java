package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.User;

public interface UserRepository {
  int save(User user);

  int update(User user);

  User findByUsername(String username);

  int deleteById(Long id);
}
