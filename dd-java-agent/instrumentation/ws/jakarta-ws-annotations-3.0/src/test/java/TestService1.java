import jakarta.jws.WebService;

@WebService
public interface TestService1 {
  String send(String message);
}
