package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.Dog;

import java.util.List;

public interface DogRepository {

  int save(Dog dog);

  int update(Dog dog);

  Dog findById(Long id);

  List<Dog> findAll();

  int deleteById(Long id);
}
