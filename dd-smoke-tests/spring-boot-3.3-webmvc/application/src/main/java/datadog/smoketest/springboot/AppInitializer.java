package datadog.smoketest.springboot;

import datadog.smoketest.springboot.model.Fruit;
import datadog.smoketest.springboot.repository.FruitRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

@Service
public class AppInitializer implements InitializingBean {
  private final FruitRepository fruitRepository;

  public AppInitializer(FruitRepository fruitRepository) {
    this.fruitRepository = fruitRepository;
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    fruitRepository.save(new Fruit("apple"));
    fruitRepository.save(new Fruit("banana"));
    fruitRepository.save(new Fruit("orange"));
  }
}
