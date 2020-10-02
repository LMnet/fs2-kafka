import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import fs2._
import fs2.concurrent.{NoneTerminatedQueue, Queue}

object Tests extends IOApp {

  class MyService[F[_]] private (
    source: Vector[String],
    indexRef: Ref[F, Int],
    queue: NoneTerminatedQueue[F, String],
  )(implicit F: Concurrent[F]) {
    def run: Stream[F, String] = {
      Stream.eval(Deferred.tryable[F, Unit]).flatMap { finished =>
        queue.dequeue.concurrently(
          Stream.repeatEval {
            finished.tryGet.flatMap {
              case Some(()) => F.unit
              case None =>
                indexRef.getAndUpdate(_ + 1).flatMap { idx =>
                  if (idx > source.size - 1) {
                    queue.enqueue1(None) >> finished.complete(())
                  } else {
                    queue.enqueue1(Some(source(idx)))
                  }
                }
            }
          }.interruptWhen(finished.get.attempt)
        )
      }.scope.onFinalizeCaseWeak { exitCase =>
        F.delay(println(s"Finalized $exitCase"))
      }
    }
  }
  object MyService {
    def apply[F[_]](source: Vector[String])(implicit F: Concurrent[F]): Resource[F, MyService[F]] = {
      Resource.make(for {
        queue <- Queue.noneTerminated[F, String]
        indexRef <- Ref[F].of(0)
      } yield new MyService[F](source, indexRef, queue)) { _ =>
        F.delay(println("HOHO"))
      }
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val strings = (0 to 100).map(_.toString).toVector

    for {
      started <- Deferred[IO, Unit]
      finished <- Deferred[IO, Unit]
      fiber <- MyService[IO](strings).use { myService =>
        myService.run.evalMap { x =>
          started.complete(()).attempt >> IO(println(x))
        }.compile.drain.uncancelable >> IO(println("OLOLO")) >> finished.complete(())
      }.start
      _ <- started.get
      _ <- fiber.cancel
      _ <- IO(println("TATA"))
      _ <- IO.race(fiber.join, finished.get).void
      _ <- IO(println("App finished"))
    } yield ExitCode.Success
  }
}
