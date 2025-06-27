package datadog.smoketest.springboot.controller;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IastSamplingController {

  @GetMapping("/multiple_vulns/{i}")
  public String multipleVulns(
      @PathVariable("i") int i,
      @RequestParam(name = "param", required = false) String paramValue,
      HttpServletRequest request,
      HttpServletResponse response)
      throws NoSuchAlgorithmException {
    // weak hash
    MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8));
    // Insecure cookie
    Cookie cookie = new Cookie("user-id", "7");
    response.addCookie(cookie);
    // weak hash
    MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8));
    // untrusted deserialization
    try {
      final ObjectInputStream ois = new ObjectInputStream(request.getInputStream());
      ois.close();
    } catch (IOException e) {
      // Ignore IOException
    }
    // weak hash
    MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8));
    return "OK";
  }

  @GetMapping("/multiple_vulns-2/{i}")
  public String multipleVulns2(
      @PathVariable("i") int i,
      @RequestParam(name = "param", required = false) String paramValue,
      HttpServletRequest request,
      HttpServletResponse response)
      throws NoSuchAlgorithmException {
    // weak hash
    MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8));
    // Insecure cookie
    Cookie cookie = new Cookie("user-id", "7");
    response.addCookie(cookie);
    // weak hash
    MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8));
    // untrusted deserialization
    try {
      final ObjectInputStream ois = new ObjectInputStream(request.getInputStream());
      ois.close();
    } catch (IOException e) {
      // Ignore IOException
    }
    // weak hash
    MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8));
    return "OK";
  }

  @PostMapping("/multiple_vulns/{i}")
  public String multipleVulnsPost(
      @PathVariable("i") int i,
      @RequestParam(name = "param", required = false) String paramValue,
      HttpServletRequest request,
      HttpServletResponse response)
      throws NoSuchAlgorithmException {
    // weak hash
    MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8));
    // Insecure cookie
    Cookie cookie = new Cookie("user-id", "7");
    response.addCookie(cookie);
    // weak hash
    MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8));
    // untrusted deserialization
    try {
      final ObjectInputStream ois = new ObjectInputStream(request.getInputStream());
      ois.close();
    } catch (IOException e) {
      // Ignore IOException
    }
    // weak hash
    MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8));
    return "OK";
  }

  @GetMapping("/different_vulns/{i}")
  public String differentVulns(
      @PathVariable("i") int i, HttpServletRequest request, HttpServletResponse response)
      throws NoSuchAlgorithmException {
    if (i == 1) {
      // weak hash
      MessageDigest.getInstance("SHA1").digest("hash1".getBytes(StandardCharsets.UTF_8));
      // Insecure cookie
      Cookie cookie = new Cookie("user-id", "7");
      response.addCookie(cookie);
      // weak hash
      MessageDigest.getInstance("SHA-1").digest("hash2".getBytes(StandardCharsets.UTF_8));
      // untrusted deserialization
      try {
        final ObjectInputStream ois = new ObjectInputStream(request.getInputStream());
        ois.close();
      } catch (IOException e) {
        // Ignore IOException
      }
    } else {
      // weak hash
      MessageDigest.getInstance("MD2").digest("hash3".getBytes(StandardCharsets.UTF_8));
      // weak hash
      MessageDigest.getInstance("MD5").digest("hash3".getBytes(StandardCharsets.UTF_8));
      // weak hash
      MessageDigest.getInstance("RIPEMD128").digest("hash3".getBytes(StandardCharsets.UTF_8));
    }
    return "OK";
  }
}
