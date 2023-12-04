package datadog.smoketest.appsec.springbootjdbcpostgresql.controller;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.AccessLog;
import datadog.smoketest.appsec.springbootjdbcpostgresql.model.User;
import datadog.smoketest.appsec.springbootjdbcpostgresql.repository.AccessLogRepository;
import datadog.smoketest.appsec.springbootjdbcpostgresql.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;

@CrossOrigin(origins = "http://localhost:8081")
@RestController
@RequestMapping("/api/user")
public class UserController {

  @Autowired
  UserRepository userRepository;

  @Autowired
  AccessLogRepository accessLogRepository;

  @PostMapping("/register")
  public ResponseEntity<String> registerUser(@RequestBody User user) {
    try {
      userRepository.save(new User(user.getUsername(), user.getPassword()));
      return new ResponseEntity<>("User was created successfully.", HttpStatus.CREATED);
    } catch (Exception e) {
      e.printStackTrace();
      return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @PostMapping("/authenticate")
  public ResponseEntity<String> authenticate(@RequestBody User user) {
    try {
      User _user = userRepository.findByUsername(user.getUsername());
      if (user.getUsername().equalsIgnoreCase(_user.getUsername()) && user.getPassword().equals(_user.getPassword())) {
        AccessLog accessLog = new AccessLog();
        accessLog.setUserId(_user.getId());
        accessLog.setDogId(1);
        accessLog.setAccessTimestamp(new Timestamp(System.currentTimeMillis()));
        accessLogRepository.save(accessLog);
        return new ResponseEntity<>("User was authorised successfully.", HttpStatus.OK);
      } else {
        return new ResponseEntity<>("Can't find username/password combination. Username=" + user.getUsername(), HttpStatus.NOT_FOUND);
      }
    } catch (Exception e) {
      e.printStackTrace();
      return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
