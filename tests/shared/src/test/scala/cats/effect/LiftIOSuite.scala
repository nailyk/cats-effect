package cats.effect

import cats.Applicative
import cats.data.IdT
import cats.mtl.LiftValue

class LiftIOSuite extends BaseSuite {
  ticked("LiftIO from LiftValue") { implicit ticker =>
    implicit val lift: LiftValue[IO, IdT[IO, *]] =
      new LiftValue[IO, IdT[IO, *]] {
        def applicativeF: Applicative[IO] = implicitly
        def applicativeG: Applicative[IdT[IO, *]] = implicitly
        def apply[A](fa: IO[A]): IdT[IO, A] = IdT(fa)
      }
    assertCompleteAs(IO.pure(42).to[IdT[IO, *]].value, 42)
  }
}
