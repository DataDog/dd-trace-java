package filters;

import akka.stream.Materializer;
import javax.inject.*;
import play.libs.concurrent.HttpExecutionContext;

@Singleton
public class Filter1 extends AbstractFilter {
  @Inject
  public Filter1(Materializer mat, HttpExecutionContext ec) {
    super("filter1", true, mat, ec);
  }
}
