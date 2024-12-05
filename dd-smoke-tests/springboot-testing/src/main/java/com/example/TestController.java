package com.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class TestController {

  @GetMapping(value = "/weak-hash")
  @ResponseBody
  public String weakHash() throws NoSuchAlgorithmException {
    MessageDigest hasher = MessageDigest.getInstance("MD5");
    hasher.digest("Message body".getBytes(StandardCharsets.UTF_8));
    return "Weak Hash page";
  }
}
