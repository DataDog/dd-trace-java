package datadog.smoketest.springboot.repository;

import datadog.smoketest.springboot.model.Fruit;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FruitRepository extends CrudRepository<Fruit, Long> {
  Optional<Fruit> findByName(@Nonnull final String name);
}
