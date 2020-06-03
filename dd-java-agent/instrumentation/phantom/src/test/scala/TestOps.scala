import java.util.concurrent.Executor

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import datadog.trace.api.Trace

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.compat.java8.FutureConverters._
import scala.util.Try
class TestOps(booksOps: BooksOps) {

  def insertBookAndWait(book: Book, ecc: ExecutionContextExecutor): Unit = {
    Await.result(booksOps.insertBook(book, ecc), 5.seconds)
  }

  def multiOperationExpression(book: Book, generalExecutionContext: ExecutionContext, phantomExecutor: ExecutionContextExecutor): Future[Boolean] = {
    implicit val ec = generalExecutionContext
    val ops = for {
      rs1 <- Future(booksOps.insertBook(book, phantomExecutor))
      rs2 <- Future{
        booksOps.setBookStatus(book.id, "In stock", phantomExecutor)
      }
      rs3 <- Future {
        booksOps.setInventory(book.id, 100, phantomExecutor)
      }
    } yield {true}
    ops
  }

  import FutureAdapter._
  @Trace
  def multiOperationExpressionPlain(book: Book, generalExecutionContext: ExecutionContext, phantomExecutor: ExecutionContextExecutor): Future[Boolean] = {
    implicit val ec = generalExecutionContext
    implicit val ec2 = phantomExecutor
    booksOps.session.executeAsync(s"UPDATE books.books set title = '${book.title}', author = '${book.author}' where id=${book.id};").asScala
      .flatMap(rs => booksOps.session.executeAsync(s"UPDATE books.books set status = 'Instock' where id=${book.id};").asScala )
      .flatMap(rs => booksOps.session.executeAsync( s"UPDATE books.books set inventory = 10 where id=${book.id};").asScala)
      .map(_ => true)
  }
}

object FutureAdapter {

  implicit class RichListenableFuture[T](val lf: ListenableFuture[T]) extends AnyVal {
    def asScala(implicit e: Executor): Future[T] = {
      val p = Promise[T]()
      lf.addListener(new Runnable {
        override def run(): Unit = {
          p.complete(Try(lf.get()))
        }
      }, e)
      p.future
    }
  }

}
