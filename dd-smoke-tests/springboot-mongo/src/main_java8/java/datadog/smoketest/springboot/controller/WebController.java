package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.mongo.Doc;
import datadog.smoketest.springboot.mongo.DocRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebController {

  @Autowired DocRepository docRepository;

  @RequestMapping("/docs")
  public List<Doc> getDocs() {
    return docRepository.findAll();
  }

  @RequestMapping("/docs/{id}")
  public Doc getDoc(@PathVariable String id) {
    return docRepository.findById(id).orElse(null);
  }

  @PostMapping("/docs")
  public Doc putDoc(@RequestBody String name) {
    return docRepository.save(new Doc(name));
  }
}
