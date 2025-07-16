/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect.testkit

import cats.Show
import cats.data.{Chain, NonEmptyChain}
import cats.effect.Concurrent
import cats.effect.kernel.{Deferred, Ref, Resource}
import cats.effect.std.{Console, Semaphore}
import cats.effect.testkit.TestConsole.TestStdIn
import cats.syntax.all._

import scala.annotation.tailrec
import scala.util.control.NoStackTrace

import java.io.EOFException
import java.nio.charset.Charset

/**
 * Implement a test version of [[cats.effect.std.Console]]
 */
final class TestConsole[F[_]: Concurrent](
    stdIn: TestStdIn[F],
    stdOutRef: Ref[F, Chain[String]],
    stdErrRef: Ref[F, Chain[String]],
    inspector: TestConsole.Inspector[F]
) extends Console[F] {
  import inspector.log

  override def readLineWithCharset(charset: Charset): F[String] =
    stdIn.readLine(charset)

  override def print[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"print($a)") *> stdOutRef.update(_.append(a.show))

  override def println[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"println($a)") *> stdOutRef.update(_.append(a.show).append("\n"))

  override def error[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"error($a)") *> stdErrRef.update(_.append(a.show))

  override def errorln[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"errorln($a)") *> stdErrRef.update(_.append(a.show).append("\n"))
}
object TestConsole {

  /**
   * Create a resource which instantiates and closes a [[TestConsole]]
   *
   * This is the preferred usage pattern, as it ensures that no fibers are left blocked on calls
   * to [[TestConsole.readLineWithCharset]]
   */
  def resource[F[_]: Concurrent]: Resource[F, (TestConsole[F], TestStdIn[F], Inspector[F])] =
    Resource.make[F, (TestConsole[F], TestStdIn[F], Inspector[F])](unsafe[F]) {
      case (_, stdIn, _) => stdIn.close.recover { case ConsoleClosedException() => () }
    }

  private def unsafe[F[_]: Concurrent]: F[(TestConsole[F], TestStdIn[F], Inspector[F])] =
    for {
      stdInStateRef <- Ref.of[F, TestStdIn.State[F]](TestStdIn.State.waiting[F])
      stdOutRef <- Ref.empty[F, Chain[String]]
      stdErrRef <- Ref.empty[F, Chain[String]]
      inspector <- Inspector.default(stdInStateRef, stdOutRef, stdErrRef)
      stdIn <- TestStdIn.default(stdInStateRef, inspector.log)
    } yield (new TestConsole[F](stdIn, stdOutRef, stdErrRef, inspector), stdIn, inspector)

  private[testkit] final case class ConsoleClosedException()
      extends IllegalStateException("Console is closed")
      with NoStackTrace

  /**
   * Allows inspection of the state of a [[TestConsole]]
   */
  trait Inspector[F[_]] {

    /**
     * @return
     *   The current contents of the associated [[TestConsole]]'s stdOut
     */
    def stdOutContents: F[String]

    /**
     * @return
     *   The current contents of the associated [[TestConsole]]'s stdErr
     */
    def stdErrContents: F[String]

    /**
     * @return
     *   A human-readable description of the activity log and current status of this instance.
     *
     * Handy for debugging failing or blocked tests.
     */
    def description: F[String]

    private[testkit] def log(msg: String): F[Unit]
  }
  object Inspector {
    def default[F[_]: Concurrent](
        stdInStateRef: Ref[F, TestStdIn.State[F]],
        stdOutRef: Ref[F, Chain[String]],
        stdErrRef: Ref[F, Chain[String]]): F[Inspector[F]] =
      Ref.empty[F, Chain[String]].map { logsRef =>
        new Inspector[F] {
          override def stdOutContents: F[String] = stdOutRef.get.map(_.mkString_(""))

          override def stdErrContents: F[String] = stdErrRef.get.map(_.mkString_(""))

          override def description: F[String] =
            (logsRef.get.map(_.mkString_("\n")), stdInStateRef.get.map(_.describe)).mapN {
              (logStr, stateStr) =>
                s"""|=== Activity Log ===
                    |$logStr
                    |=== Current State ===
                    |$stateStr""".stripMargin
            }

          override private[testkit] def log(msg: String): F[Unit] =
            logsRef.update(_.append(msg))
        }
      }
  }

  trait TestStdIn[F[_]] {

    /**
     * Write a string to the simulated stdIn using the system-default charset
     *
     * Blocked calls to [[TestConsole.readLineWithCharset]] will be woken up if `str` contains
     * one or more lines.
     *
     * @note
     *   Blocked calls will be woken in a first-in-first-out order.
     */
    def write[A](value: A)(implicit S: Show[A]): F[Unit]

    /**
     * Write a string to the simulated stdIn
     *
     * Blocked calls to [[TestConsole.readLineWithCharset]] will be woken up if `str` contains
     * one or more lines.
     *
     * @note
     *   Blocked calls will be woken in a first-in-first-out order.
     */
    def write[A](value: A, charset: Charset)(implicit S: Show[A]): F[Unit]

    /**
     * Write a string and a newline to the simulated stdIn with the system-default charset
     *
     * At least one blocked call to [[TestConsole.readLineWithCharset]] will be woken up, if it
     * exists.
     *
     * @note
     *   Blocked calls will be woken in a first-in-first-out order.
     */
    def writeln[A](value: A)(implicit S: Show[A]): F[Unit]

    /**
     * Write a string and a newline to the simulated stdIn
     *
     * At least one blocked call to [[TestConsole.readLineWithCharset]] will be woken up, if it
     * exists.
     *
     * @note
     *   Blocked calls will be woken in a first-in-first-out order.
     */
    def writeln[A](value: A, charset: Charset)(implicit S: Show[A]): F[Unit]

    private[testkit] def readLine(charset: Charset): F[String]
    private[testkit] def close: F[Unit]
  }

  object TestStdIn {
    def default[F[_]](stateRef: Ref[F, TestStdIn.State[F]], log: String => F[Unit])(
        implicit F: Concurrent[F]): F[TestStdIn[F]] =
      (Semaphore[F](1L), Ref.of[F, Int](0)).mapN { (semaphore, readIdRef) =>
        new TestStdIn[F] {
          private val defaultCharset = Charset.defaultCharset()

          private def streamClosed = new EOFException("End Of File")

          override def write[A](value: A)(implicit S: Show[A]): F[Unit] =
            write(value, defaultCharset)

          override def write[A](value: A, charset: Charset)(implicit S: Show[A]): F[Unit] =
            log(show"Writing to stdIn: $value") *> writeImpl(State.Chunk(value.show, charset))

          override def writeln[A](value: A)(implicit S: Show[A]): F[Unit] =
            writeln(value, defaultCharset)

          override def writeln[A](value: A, charset: Charset)(implicit S: Show[A]): F[Unit] =
            log(show"Writing line to stdIn: $value") *> writeImpl(
              State.Chunk(show"$value\n", charset))

          private def writeImpl(chunk: State.Chunk): F[Unit] =
            if (chunk.isEmpty) F.unit
            else
              semaphore.permit.use { _ =>
                stateRef.get.flatMap {
                  case State.Closed() => F.raiseError(ConsoleClosedException())
                  case ready: TestStdIn.State.Ready[F] => stateRef.set(ready.push(chunk))
                  case oldState: TestStdIn.State.Waiting[F] =>
                    val (lines, newBuffer) = oldState.buffer.append(chunk)
                    if (lines.isEmpty) stateRef.set(oldState.replaceBuffer(newBuffer))
                    else {
                      def loop(
                          remainingLines: Chain[State.Line],
                          remainingRequests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]])
                          : F[State[F]] =
                        (remainingLines.uncons, remainingRequests.uncons) match {
                          case (None, None) => State.waiting[F].pure[F]
                          case (None, Some(_)) =>
                            State.waiting[F](remainingRequests, newBuffer).pure[F]
                          case (Some((nextLine, otherLines)), None) =>
                            State.ready[F](nextLine, otherLines, newBuffer).pure[F]
                          case (
                                Some((nextLine, otherLines)),
                                Some((nextRequest, otherRequests))) =>
                            nextRequest.complete(nextLine.bytes.asRight) >> loop(
                              otherLines,
                              otherRequests)
                        }

                      loop(lines, oldState.requests).flatMap(stateRef.set)
                    }
                }
              }

          override private[testkit] def readLine(charset: Charset): F[String] =
            readIdRef.getAndUpdate(_ + 1).flatMap { readId =>
              semaphore
                .permit
                .use { _ =>
                  log(s"Reading stdIn [id: $readId]") *>
                    stateRef.get.flatMap {
                      case State.Closed() =>
                        F.raiseError[Deferred[F, Either[Throwable, Array[Byte]]]](streamClosed)
                      case ready: TestStdIn.State.Ready[F] =>
                        val (line, newState) = ready.shift

                        stateRef.set(newState) *>
                          Deferred[F, Either[Throwable, Array[Byte]]].flatTap(
                            _.complete(line.bytes.asRight))
                      case waiting: TestStdIn.State.Waiting[F] =>
                        Deferred[F, Either[Throwable, Array[Byte]]].flatTap(d =>
                          stateRef.set(waiting.addRequest(d)))
                    }
                }
                .flatMap(_.get)
                .flatMap(_.traverse(bytes =>
                  Concurrent[F].catchNonFatal(new String(bytes, charset))))
                .flatTap {
                  case Left(ex) => log(s"Read from stdIn failed [id: $readId]: $ex")
                  case Right(line) => log(s"Read from stdIn [id: $readId]: $line")
                }
                .rethrow
            }

          override private[testkit] def close: F[Unit] =
            semaphore.permit.use { _ =>
              stateRef
                .get
                .flatTap(_ => log("Closing stdIn"))
                .flatMap {
                  case State.Closed() => F.unit
                  case State.Ready(lines, partial) =>
                    log(s"Discarding ${lines.length} lines and ${partial.chunks.length} bytes from stdIn") *>
                      stateRef.set(State.closed)
                  case State.Waiting(requests, buffer) =>
                    log(s"Discarding ${buffer.chunks.length} bytes from stdIn")
                      .unlessA(buffer.chunks.isEmpty) *>
                      log(s"Notifying ${requests.length} pending read requests")
                        .unlessA(requests.isEmpty) *>
                      stateRef.set(State.closed) *>
                      requests.traverse_(_.complete(streamClosed.asLeft))
                }
                .flatTap(_ => log("Closed stdIn"))
            }
        }
      }

    private[testkit] sealed trait State[F[_]] {
      def describe: String = this match {
        case State.Closed() => "Closed"
        case State.Ready(lines, partial) =>
          val linesStr = lines.mkString_("\n")
          val partialStr =
            if (partial.isEmpty) "No partial line"
            else s"Partial line: '${partial.render}'"

          s"""Ready for read
             |$partialStr
             |--- Complete Lines ---
             |$linesStr""".stripMargin
        case State.Waiting(requests, buffer) =>
          val bufferStr =
            if (buffer.isEmpty) "No partial line"
            else s"Partial line: '${buffer.render}'"
          s"""Waiting for write
             |Pending requests: ${requests.length}
             |$bufferStr""".stripMargin
      }
    }
    private[testkit] object State {
      def closed[F[_]]: State[F] = Closed()

      def ready[F[_]](
          firstLine: Line,
          otherLines: Chain[Line],
          partial: PartialLine): State[F] =
        Ready(NonEmptyChain.fromChainPrepend(firstLine, otherLines), partial)

      def waiting[F[_]]: State[F] = Waiting(Chain.empty, PartialLine.empty)
      def waiting[F[_]](buffer: PartialLine): State[F] = Waiting(Chain.empty, buffer)
      def waiting[F[_]](
          requests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]],
          buffer: PartialLine): State[F] = Waiting(requests, buffer)

      final case class Chunk(value: String, charset: Charset) {
        def bytes: Array[Byte] = value.getBytes(charset)

        def isEmpty: Boolean = value.isEmpty

        def modify(f: String => String): Chunk = Chunk(f(value), charset)

        def split(char: Char): Option[(Chunk, Chunk)] = {
          val idx = value.indexOf(char.toInt)
          if (idx === -1) None
          else {
            val (head, tail) = value.splitAt(idx)
            Some((Chunk(head, charset), Chunk(tail.drop(1), charset)))
          }
        }
      }

      object Chunk {
        implicit val show: Show[Chunk] = Show.show(_.value)
      }

      /**
       * Chunks of a line which cannot be read from stdIn until a newline is written
       */
      final case class PartialLine(chunks: Chain[Chunk]) {
        def isEmpty: Boolean = chunks.forall(_.isEmpty)

        def render: String = chunks.mkString_("")

        def toLine: Line = Line(chunks)

        def append(chunk: Chunk): (Chain[Line], PartialLine) =
          if (chunk.value.startsWith("\n"))
            PartialLine.empty.append(chunk.modify(_.drop(1))).leftMap(_.prepend(toLine))
          else if (chunk.value.endsWith("\n")) {
            val (lines, lastLine) = append(chunk.modify(_.dropRight(1)))
            lines.append(lastLine.toLine) -> PartialLine.empty
          } else {
            if (chunk.isEmpty) (Chain.empty, this)
            else {
              @tailrec
              def loop(accum: Chain[Line], remaining: Chunk): (Chain[Line], PartialLine) =
                if (remaining.isEmpty) (accum, PartialLine.empty)
                else {
                  remaining.split('\n') match {
                    case None => (accum, PartialLine.one(remaining))
                    case Some((head, tail)) => loop(accum.append(Line.one(head)), tail)
                  }
                }

              chunk.split('\n') match {
                case Some((head, tail)) =>
                  loop(Chain.one(Line(chunks.append(head))), tail)
                case None =>
                  if (isEmpty) (Chain.empty, PartialLine.one(chunk))
                  else (Chain.empty, PartialLine(chunks.append(chunk)))
              }
            }
          }
      }

      object PartialLine {
        def one(c: Chunk): PartialLine = PartialLine(Chain.one(c))

        def empty: PartialLine = PartialLine(Chain.empty)
      }

      /**
       * Lines ready to be read from stdIn
       */
      final case class Line(chunks: Chain[Chunk]) {
        def isEmpty: Boolean = chunks.forall(_.isEmpty)

        def render: String = chunks.mkString_("")

        def bytes: Array[Byte] =
          chunks.map(_.bytes).toVector.toArray.flatten
      }

      object Line {
        def one(chunk: Chunk): Line = Line(Chain.one(chunk))

        def empty: Line = Line(Chain.empty)

        implicit val show: Show[Line] = Show.show(_.render)
      }

      /**
       * StdIn will reject reads and writes
       */
      final case class Closed[F[_]]() extends State[F]

      /**
       * StdIn has at least one line ready to be read
       *
       * Transitions to Waiting when `lines` can no longer be created
       */
      final case class Ready[F[_]](lines: NonEmptyChain[Line], partial: PartialLine)
          extends State[F] {
        def push(chunk: Chunk): State[F] = {
          val (newLines, newPartial) = partial.append(chunk)
          Ready[F](lines.appendChain(newLines), newPartial)
        }

        def shift: (Line, State[F]) = {
          val newState =
            NonEmptyChain.fromChain(lines.tail).fold(waiting[F](partial))(Ready(_, partial))

          (lines.head, newState)
        }
      }

      /**
       * StdIn cannot accept writes because it doesn't have at least one complete line
       *
       * Transitions to Ready when a newline is written to the stream
       */
      final case class Waiting[F[_]](
          requests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]],
          buffer: PartialLine)
          extends State[F] {
        def replaceBuffer(newBuffer: PartialLine): State[F] =
          Waiting(requests, newBuffer)

        def addRequest(request: Deferred[F, Either[Throwable, Array[Byte]]]): State[F] =
          Waiting(requests.append(request), buffer)
      }
    }
  }
}
