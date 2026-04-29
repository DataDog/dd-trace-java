package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.model.Fruit;
import datadog.smoketest.springboot.repository.FruitRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fruits")
public class FruitController {

  private final FruitRepository fruitRepository;

  public FruitController(FruitRepository fruitRepository) {
    this.fruitRepository = fruitRepository;
  }

  @GetMapping
  public Iterable<Fruit> listFruits() {
    return fruitRepository.findAll();
  }

  @GetMapping("/{name}")
  public ResponseEntity<Fruit> findOneFruit(@PathVariable("name") final String name) {
    return fruitRepository
        .findByName(name)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
