package datadog.smoketest.appsec.springbootjdbcpostgresql.controller;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.Dog;
import datadog.smoketest.appsec.springbootjdbcpostgresql.repository.DogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@CrossOrigin(origins = "http://localhost:8081")
@RestController
@RequestMapping("/api")
public class DogController {

  @Autowired
  DogRepository dogRepository;

  @GetMapping("/dogs")
  public ResponseEntity<?> getAllDogs() {
    try {

      List<Dog> dogs = new ArrayList<>(dogRepository.findAll());

      if (dogs.isEmpty()) {
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
      }

      return new ResponseEntity<>(dogs, HttpStatus.OK);
    } catch (Exception e) {
      e.printStackTrace();
      return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @GetMapping("/dogs/{id}")
  public ResponseEntity<Dog> getDogById(@PathVariable("id") long id) {
    Dog dog = dogRepository.findById(id);

    if (dog != null) {
      return new ResponseEntity<>(dog, HttpStatus.OK);
    } else {
      return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
  }

  @PostMapping("/dogs")
  public ResponseEntity<String> createDog(@RequestBody Dog dog) {
    try {
      dogRepository.save(new Dog(dog.getName(), dog.getBreed(), dog.getBirthDate(), dog.getProfileImageUrl(), dog.getPublicDetails(), dog.getCreditCardNumber(), dog.getSocialIdNumber()));
      return new ResponseEntity<>("Dog was created successfully.", HttpStatus.CREATED);
    } catch (Exception e) {
      e.printStackTrace();
      return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @PutMapping("/dogs/{id}")
  public ResponseEntity<String> updateDog(
      @PathVariable("id") long id, @RequestBody Dog dog) {
    Dog _dog = dogRepository.findById(id);

    if (_dog != null) {
      _dog.setId(id);
      _dog.setName(dog.getName());
      _dog.setBreed(dog.getBreed());
      _dog.setProfileImageUrl(dog.getProfileImageUrl());
      _dog.setPublicDetails(dog.getPublicDetails());
      _dog.setCreditCardNumber(dog.getCreditCardNumber());
      _dog.setSocialIdNumber(dog.getSocialIdNumber());

      dogRepository.update(_dog);
      return new ResponseEntity<>("Dog was updated successfully.", HttpStatus.OK);
    } else {
      return new ResponseEntity<>("Cannot find Dog with id=" + id, HttpStatus.NOT_FOUND);
    }
  }

  @DeleteMapping("/dogs/{id}")
  public ResponseEntity<String> deleteDog(@PathVariable("id") long id) {
    try {
      int result = dogRepository.deleteById(id);
      if (result == 0) {
        return new ResponseEntity<>("Cannot find Dog with id=" + id, HttpStatus.OK);
      }
      return new ResponseEntity<>("Dog was deleted successfully.", HttpStatus.OK);
    } catch (Exception e) {
      e.printStackTrace();
      return new ResponseEntity<>("Cannot delete Dog.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
