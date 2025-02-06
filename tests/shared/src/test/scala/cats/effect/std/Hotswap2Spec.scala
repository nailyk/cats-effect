/*
 * Copyright 2020-2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats
package effect
package std

import cats.syntax.all._
import cats.effect.Resource
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntimeConfig

import scala.concurrent.duration._

class Hotswap2Spec extends BaseSpec { outer =>

  sequential

  def logged(log: Ref[IO, List[String]], name: String): Resource[IO, Unit] =
    Resource.make(log.update(_ :+ s"open $name"))(_ => log.update(_ :+ s"close $name"))

  "Hotswap2" should {
    "run finalizer of target run when Hotswap2 is finalized" in real {
      val op = for {
        log <- Ref.of[IO, List[String]](List())
        _ <- Hotswap2[IO, Unit](logged(log, "a")).use(_ => IO.unit)
        value <- log.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "close a"))
        }
      }
    }

    "acquire new resource and finalize old resource on swap" in real {
      val op = for {
        log <- Ref.of[IO, List[String]](List())
        _ <-
          Hotswap2[IO, Unit](logged(log, "a")).use(_.swap(logged(log, "b")))
        value <- log.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "open b", "close a", "close b"))
        }
      }
    }

    "finalize old resource on clear" in real {
      val op = for {
        log <- Ref.of[IO, List[String]](List())
        _ <- Hotswap2[IO, Option[Unit]](logged(log, "a").map(_.some)).use { hotswap =>
          hotswap.clear *> hotswap.swap(logged(log, "b").map(_.some))
        }
        value <- log.get
      } yield value

      op.flatMap { res =>
        IO {
          res must beEqualTo(List("open a", "close a", "open b", "close b"))
        }
      }
    }

    "not release current resource while it is in use" in ticked { implicit ticker =>
      val r0 = Resource.make(IO.ref(1))(_.set(10))
      val r1 = Resource.make(IO.ref(2))(_.set(20))
      val r2 = Resource.make(IO.ref(3))(_.set(30))
      val go = Hotswap2[IO, Ref[IO, Int]](r0).use { hs =>
        hs.swap(r1) *> (IO.sleep(1.second) *> hs.swap(r2)).background.surround {
          hs.get.use { ref =>
            val notReleased = ref.get.flatMap(b => IO(b must beEqualTo(2)))
            notReleased *> IO.sleep(2.seconds) *> notReleased.void
          }
        }
      }

      go must completeAs(())
    }

    "not finalize Hotswap2 while resource is in use" in ticked { implicit ticker =>
      val r0 = Resource.make(IO.ref(1))(_.set(10))
      val r1 = Resource.make(IO.ref(2))(_.set(20))
      val go = Hotswap2[IO, Ref[IO, Int]](r0).allocated.flatMap {
        case (hs, fin) =>
          hs.swap(r1) *> (IO.sleep(1.second) *> fin).background.surround {
            hs.get.use { ref =>
              val notReleased = ref.get.flatMap(b => IO(b must beEqualTo(2)))
              notReleased *> IO.sleep(2.seconds) *> notReleased.void
            }
          }
      }

      go must completeAs(())
    }

    "resource can be accessed concurrently" in ticked { implicit ticker =>
      val go = Hotswap2[IO, Unit](Resource.unit).use { hs =>
        hs.get.useForever.background.surround {
          IO.sleep(1.second) *> hs.get.use_
        }
      }

      go must completeAs(())
    }

    "not block current resource while swap is instantiating new one" in ticked {
      implicit ticker =>
        val go = Hotswap2[IO, Unit](Resource.unit).use { hs =>
          hs.swap(IO.sleep(1.minute).toResource).start *>
            IO.sleep(5.seconds) *>
            hs.get.use_.timeout(1.second).void
        }
        go must completeAs(())
    }

    "successfully cancel during swap and run finalizer if cancellation is requested while waiting for get to release" in ticked {
      implicit ticker =>
        val go = Ref.of[IO, List[String]](List()).flatMap { log =>
          Hotswap2[IO, Unit](logged(log, "a")).use { hs =>
            for {
              _ <- hs.get.evalMap(_ => IO.sleep(1.minute)).use_.start
              _ <- IO.sleep(2.seconds)
              _ <- hs.swap(logged(log, "b")).timeoutTo(1.second, IO.unit)
              value <- log.get
            } yield value
          }
        }

        go must completeAs(List("open a", "open b", "close b"))
    }

    "swap is safe to concurrent cancellation" in ticked { implicit ticker =>
      val go = IO.ref(false).flatMap { open =>
        Hotswap2[IO, Unit](Resource.unit)
          .use { hs =>
            hs.swap(Resource.make(open.set(true))(_ =>
              open.getAndSet(false).map(_ should beTrue).void))
          }
          .race(IO.unit) *> open.get.map(_ must beFalse)
      }

      TestControl.executeEmbed(go, IORuntimeConfig(1, 2)).replicateA_(1000) must completeAs(())
    }

    "getOpt should not acquire a lock when there is no resource present" in ticked {
      implicit ticker =>
        val go = Hotswap2.empty[IO, Unit].use { hs =>
          hs.getOpt.useForever.start *>
            IO.sleep(2.seconds) *>
            hs.swap(Resource.unit.map(_.some))
        }
        go must completeAs(())
    }
  }
}
