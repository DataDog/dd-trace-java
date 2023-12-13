package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.AccessLog;

import java.util.List;

public interface AccessLogRepository {
  int save(AccessLog accessLog);

  List<AccessLog> findByUserId(Long id);

  List<AccessLog> findByDogId(Long id);
}
