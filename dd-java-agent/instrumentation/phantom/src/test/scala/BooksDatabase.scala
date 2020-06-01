import java.util.UUID

import com.outworkers.phantom.Table
import com.outworkers.phantom.connectors.{CassandraConnection, ContactPoint}
import com.outworkers.phantom.database.{Database, DatabaseProvider}
import com.outworkers.phantom.dsl._
case class Book(
               id: UUID,
               title: String,
               author: String
               )

abstract class Books extends Table[Books, Book] {
  object id extends UUIDColumn with PartitionKey
  object title extends StringColumn
  object author extends StringColumn
}

class BooksDatabase(override val connector: CassandraConnection) extends Database[BooksDatabase](connector) {
  object Books extends Books with Connector
}

object EmbeddedBooksDatabase extends BooksDatabase(ContactPoint.embedded.keySpace("books")) {

}

object BooksDatabaseUtils extends DatabaseProvider[BooksDatabase] {

  import scala.concurrent.duration._

  override def database: BooksDatabase = EmbeddedBooksDatabase

  def create: Unit = database.create(5.seconds)(scala.concurrent.ExecutionContext.Implicits.global)

}
