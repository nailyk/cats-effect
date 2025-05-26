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

import cats.{Parallel, Show}
import cats.data.{Chain, NonEmptyChain}
import cats.effect.Concurrent
import cats.effect.kernel.{Deferred, Ref, Resource}
import cats.effect.std.{Console, Semaphore}
import cats.effect.testkit.TestConsole.{ConsoleClosedException, TestStdInState}
import cats.effect.testkit.TestConsole.TestStdInState._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.util.control.NoStackTrace

import java.io.EOFException
import java.nio.charset.Charset

/**
 * Implement a test version of [[cats.effect.std.Console]]
 */
final class TestConsole[F[_]: Parallel](
    stdInSemaphore: Semaphore[F],
    stdInStateRef: Ref[F, TestStdInState[F]],
    stdOutRef: Ref[F, Chain[String]],
    stdErrRef: Ref[F, Chain[String]],
    logsRef: Ref[F, Chain[String]],
    readIdRef: Ref[F, Int]
)(implicit F: Concurrent[F])
    extends Console[F] {
  private val defaultCharset = Charset.defaultCharset()
  private def streamClosed = new EOFException("End Of File")
  private def log(msg: String): F[Unit] = logsRef.update(_.append(msg))

  /**
   * Write a string to the simulated stdIn
   *
   * Blocked calls to [[readLineWithCharset]] will be woken up if `str` contains one or more
   * lines.
   *
   * @note
   *   Blocked calls will be woken in a first-in-first-out order.
   */
  def write[A](value: A, charset: Charset = defaultCharset)(implicit S: Show[A]): F[Unit] =
    log(show"Writing to stdin: $value") *> writeImpl(Chunk(value.show, charset))

  /**
   * Write a string and a newline to the simulated stdIn
   *
   * At least one blocked call to [[readLineWithCharset]] will be woken up, if it exists.
   *
   * @note
   *   Blocked calls will be woken in a first-in-first-out order.
   */
  def writeln[A](value: A, charset: Charset = defaultCharset)(implicit S: Show[A]): F[Unit] =
    log(show"Writing line to stdin: $value") *> writeImpl(Chunk(show"$value\n", charset))

  private def writeImpl(chunk: Chunk): F[Unit] =
    if (chunk.isEmpty) F.unit
    else
      stdInSemaphore.permit.use { _ =>
        stdInStateRef.get.flatMap {
          case Closed() => F.raiseError(ConsoleClosedException())
          case Ready(lines, partial) =>
            val (newLines, newPartial) = partial.append(chunk)
            stdInStateRef.set(Ready[F](lines.appendChain(newLines), newPartial))
          case Waiting(requests, buffer) =>
            val (lines, partial) = buffer.append(chunk)
            if (lines.isEmpty)
              stdInStateRef.set(Waiting[F](requests, partial))
            else {
              def loop(
                  remainingLines: Chain[Line],
                  remainingRequests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]])
                  : F[TestStdInState[F]] =
                (remainingLines.uncons, remainingRequests.uncons) match {
                  case (None, None) =>
                    Waiting[F](Chain.empty, PartialLine.empty).pure[F].widen
                  case (None, Some(_)) =>
                    Waiting[F](remainingRequests, partial).pure[F].widen
                  case (Some((nextLine, otherLines)), None) =>
                    Ready[F](NonEmptyChain.fromChainPrepend(nextLine, otherLines), partial)
                      .pure[F]
                      .widen
                  case (Some((nextLine, otherLines)), Some((nextRequest, otherRequests))) =>
                    nextRequest
                      .complete(nextLine.bytes.asRight) >> loop(otherLines, otherRequests)
                }

              loop(lines, requests).flatMap(stdInStateRef.set)
            }
        }
      }

  override def readLineWithCharset(charset: Charset): F[String] =
    readIdRef.getAndUpdate(_ + 1).flatMap { readId =>
      stdInSemaphore
        .permit
        .use { _ =>
          log(s"Reading stdIn [id: $readId]") *>
            stdInStateRef.get.flatMap {
              case Closed() =>
                F.raiseError[Deferred[F, Either[Throwable, Array[Byte]]]](streamClosed)
              case Ready(lines, partial) =>
                val newState =
                  NonEmptyChain
                    .fromChain(lines.tail)
                    .fold[TestStdInState[F]](Waiting(Chain.empty, PartialLine.empty))(
                      Ready(_, partial))

                stdInStateRef.set(newState) *>
                  Deferred[F, Either[Throwable, Array[Byte]]].flatTap(
                    _.complete(lines.head.bytes.asRight))
              case Waiting(requests, buffer) =>
                Deferred[F, Either[Throwable, Array[Byte]]].flatTap(d =>
                  stdInStateRef.set(Waiting(requests.append(d), buffer)))
            }
        }
        .flatMap(_.get)
        .flatMap(_.traverse(bytes => Concurrent[F].catchNonFatal(new String(bytes, charset))))
        .flatTap {
          case Left(ex) => log(s"Read from stdin failed [id: $readId]: $ex")
          case Right(line) => log(s"Read from stdin [id: $readId]: $line")
        }
        .rethrow
    }

  override def print[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"print($a)") *> stdOutRef.update(_.append(a.show))

  override def println[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"println($a)") *> stdOutRef.update(_.append(a.show).append("\n"))

  override def error[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"error($a)") *> stdErrRef.update(_.append(a.show))

  override def errorln[A](a: A)(implicit S: Show[A]): F[Unit] =
    log(show"errorln($a)") *> stdErrRef.update(_.append(a.show).append("\n"))

  /**
   * Close the TestConsole
   *
   * Any blocked calls to [[readLineWithCharset]] terminate with a raised
   * [[java.io.EOFException]]
   */
  private def close: F[Unit] = stdInSemaphore.permit.use { _ =>
    stdInStateRef
      .get
      .flatTap(_ => log("Closing"))
      .flatMap {
        case Closed() => F.unit
        case Ready(lines, partial) =>
          log(s"Discarding ${lines.length} lines and ${partial.chunks.length} bytes from stdIn") *>
            stdInStateRef.set(Closed[F]())
        case Waiting(requests, buffer) =>
          log(s"Discarding ${buffer.chunks.length} bytes from stdIn")
            .unlessA(buffer.chunks.isEmpty) *>
            log(s"Notifying ${requests.length} pending read requests")
              .unlessA(requests.isEmpty) *>
            stdInStateRef.set(Closed[F]()) *> requests.parTraverse_(
              _.complete(streamClosed.asLeft))
      }
      .flatTap(_ => log("Closed"))
  }

  /**
   * @return
   *   The current contents of stdOut
   */
  def stdOutContents: F[String] = stdOutRef.get.map(_.mkString_(""))

  /**
   * @return
   *   The current contents of stdErr
   */
  def stdErrContents: F[String] = stdErrRef.get.map(_.mkString_(""))

  /**
   * @return
   *   A human-readable description of the activity log and current status of this instance.
   *
   * Handy for debugging failing or blocked tests.
   */
  def activityLog: F[String] =
    (logsRef.get.map(_.mkString_("\n")), stdStateDescription).mapN { (logStr, stateStr) =>
      s"""|=== Activity Log ===
          |$logStr
          |=== Current State ===
          |$stateStr""".stripMargin
    }

  /**
   * Clear the human-readable activity log
   */
  def clearLog: F[Unit] = logsRef.set(Chain.empty)

  private def stdStateDescription: F[String] = stdInStateRef.get.map {
    case Closed() => "Closed"
    case Ready(lines, partial) =>
      val linesStr = lines.mkString_("\n")
      val partialStr =
        if (partial.isEmpty) "No partial line"
        else s"Partial line: '${partial.render}'"

      s"""Ready for read
         |$partialStr
         |--- Complete Lines ---
         |$linesStr""".stripMargin
    case Waiting(requests, buffer) =>
      val bufferStr =
        if (buffer.isEmpty) "No partial line"
        else s"Partial line: '${buffer.render}'"
      s"""Waiting for read
         |Pending requests: ${requests.length}
         |$bufferStr""".stripMargin
  }
}
object TestConsole {

  /**
   * Create a resource which instantiates and closes a [[TestConsole]]
   *
   * This is the preferred usage pattern, as it ensures that no fibers are left blocked on calls
   * to [[TestConsole.readLineWithCharset]]
   */
  def resource[F[_]: Concurrent: Parallel]: Resource[F, TestConsole[F]] =
    Resource.make[F, TestConsole[F]](unsafe[F])(_.close.recover {
      case ConsoleClosedException() => ()
    })

  private def unsafe[F[_]: Concurrent: Parallel]: F[TestConsole[F]] =
    (
      Semaphore[F](1L),
      Ref.of[F, TestStdInState[F]](TestStdInState.Waiting[F](Chain.empty, PartialLine.empty)),
      Ref.empty[F, Chain[String]],
      Ref.empty[F, Chain[String]],
      Ref.empty[F, Chain[String]],
      Ref.of[F, Int](0)
    ).mapN(new TestConsole[F](_, _, _, _, _, _))

  private[testkit] final case class ConsoleClosedException()
      extends IllegalStateException("Console is closed")
      with NoStackTrace

  private[testkit] sealed trait TestStdInState[F[_]]
  private[testkit] object TestStdInState {
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

    final case class Closed[F[_]]() extends TestStdInState[F]
    final case class Ready[F[_]](lines: NonEmptyChain[Line], partial: PartialLine)
        extends TestStdInState[F]

    final case class Waiting[F[_]](
        requests: Chain[Deferred[F, Either[Throwable, Array[Byte]]]],
        buffer: PartialLine)
        extends TestStdInState[F]
  }
}
