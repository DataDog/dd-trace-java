package datadog.smoketest.springboot.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DocRepository extends MongoRepository<Doc, String> {}
