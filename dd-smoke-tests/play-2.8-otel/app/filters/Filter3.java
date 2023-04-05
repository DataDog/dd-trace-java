package filters;

import akka.stream.Materializer;
import javax.inject.*;
import play.libs.concurrent.HttpExecutionContext;

@Singleton
public class Filter3 extends AbstractFilter {
  @Inject
  public Filter3(Materializer mat, HttpExecutionContext ec) {
    super("filter3", mat, ec);
  }
}
