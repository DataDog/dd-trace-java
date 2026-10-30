package com.example.jsonbench;

import com.example.jsonbench.model.Dtos.Order;
import com.example.jsonbench.model.Dtos.OrderResponse;
import com.example.jsonbench.model.Dtos.SmallDto;
import com.example.jsonbench.model.Samples;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BenchController {

  private static final List<SmallDto> LIST_100 = Samples.smallList(100);
  private static final String TEXT_5K = buildText();

  private final ObjectMapper mapper;
  private final byte[] requestBody;

  public BenchController(ObjectMapper mapper) throws IOException {
    this.mapper = mapper;
    this.requestBody = mapper.writeValueAsBytes(Samples.requestOrder());
  }

  @GetMapping(value = "/nojson", produces = MediaType.TEXT_PLAIN_VALUE)
  public String noJson() {
    return TEXT_5K;
  }

  @PostMapping(
      value = "/json",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public OrderResponse json(@RequestBody Order order) {
    return Samples.respond(order);
  }

  @GetMapping(value = "/jsonlist", produces = MediaType.APPLICATION_JSON_VALUE)
  public List<SmallDto> jsonList() {
    return LIST_100;
  }

  @PostMapping(
      value = "/newmapper",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public byte[] newMapper(@RequestBody byte[] body) throws IOException {
    ObjectMapper perRequest = new ObjectMapper();
    Order order = perRequest.readValue(body, Order.class);
    return perRequest.writeValueAsBytes(Samples.respond(order));
  }

  @GetMapping(value = "/bench/request-body", produces = MediaType.APPLICATION_JSON_VALUE)
  public byte[] requestBody() {
    return requestBody;
  }

  private static String buildText() {
    StringBuilder sb = new StringBuilder(5200);
    int i = 0;
    while (sb.length() < 5000) {
      sb.append("line ").append(i).append(": the quick brown fox jumps over the lazy dog\n");
      i++;
    }
    return sb.toString();
  }
}
