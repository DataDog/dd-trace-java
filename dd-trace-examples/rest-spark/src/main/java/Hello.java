import com.datadoghq.trace.resolver.DDTracerFactory;
import com.mongodb.client.MongoCollection;
import io.opentracing.ActiveSpan;
import io.opentracing.Tracer;
import org.bson.Document;
import com.mongodb.client.MongoDatabase;
import java.util.Arrays;

import static spark.Spark.*;

public class Hello {
    private static MongoDatabase mDatabase;
    private static Tracer mTracer;

    public static void main(String[] args) {
        // Init the tracer from the configuration file
        mTracer = DDTracerFactory.createFromConfigurationFile();
        io.opentracing.util.GlobalTracer.register(mTracer);

        // initialize the Mongo database
        mDatabase = MongoDriver.getDatabase("rest_spark");

        // our routes
        get("/healthz", (req, res) -> "OK!");
        get("/key/:id", (req, res) -> {
            try (ActiveSpan activeSpan = mTracer.buildSpan("spark.request").startActive()) {
                activeSpan.setTag("http.url", req.url());

                String id = req.params(":id");

                // create a collection
                Document doc = new Document("name", "MongoDB")
                        .append("type", "database")
                        .append("identifier", id)
                        .append("versions", Arrays.asList("v3.2", "v3.0", "v2.6"))
                        .append("info", new Document("x", 203).append("y", 102));

                MongoCollection<Document> collection = mDatabase.getCollection("calls");
                collection.insertOne(doc);

                // write the count somewhere
                System.out.println(collection.count());

                return "Stored!";
            }
        });
    }
}
