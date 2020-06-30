package io.janstenpickle.trace4cats.avro.server

import java.net.InetSocketAddress

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.syntax.either._
import cats.syntax.flatMap._
import fs2.io.tcp.{SocketGroup => TCPSocketGroup}
import fs2.io.udp.{SocketGroup => UDPSocketGroup}
import fs2.{Chunk, Pipe, Pull, Stream}
import io.janstenpickle.trace4cats.avro.{agentPort, AvroInstances}
import io.janstenpickle.trace4cats.model.Batch
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DecoderFactory

object AvroServer {

  private def buffer[F[_]]: Pipe[F, Byte, Chunk[Byte]] = bytes => {
    def go(lastBytes: (Byte, Byte), state: List[Byte], stream: Stream[F, Byte]): Pull[F, Chunk[Byte], Unit] =
      stream.pull.uncons1.flatMap {
        case Some((hd, tl)) =>
          val newLastBytes = (lastBytes._2, hd)
          val newState = hd :: state
          if (newLastBytes == (0xC4.byteValue -> 0x02.byteValue))
            Pull.output1(Chunk.apply(newState.drop(2).reverse: _*)) >> go((0, 0), List.empty, tl)
          else
            go(newLastBytes, newState, tl)

        case None => Pull.done
      }

    go((0, 0), List.empty, bytes).stream
  }

  private def decode[F[_]: Sync](schema: Schema)(bytes: Chunk[Byte]): F[Batch] =
    Sync[F]
      .delay {
        val reader = new GenericDatumReader[Any](schema)
        val decoder = DecoderFactory.get.binaryDecoder(bytes.toArray, null)
        val record = reader.read(null, decoder)

        record
      }
      .flatMap { record =>
        Sync[F].fromEither(AvroInstances.batchCodec.decode(record, schema).leftMap(_.throwable))
      }

  def tcp[F[_]: Concurrent: ContextShift](
    blocker: Blocker,
    sink: Pipe[F, Batch, Unit],
    port: Int = agentPort,
  ): Resource[F, Stream[F, Unit]] =
    for {
      avroSchema <- Resource.liftF(AvroInstances.batchSchema[F])
      socketGroup <- TCPSocketGroup(blocker)
      address <- Resource.liftF(Sync[F].delay(new InetSocketAddress(port)))
    } yield
      socketGroup
        .server(address)
        .map { serverResource =>
          Stream.resource(serverResource).flatMap { server =>
            server.reads(8192).through(buffer[F]).evalMap(decode[F](avroSchema)).through(sink)
          }
        }
        .parJoin(100)

  def udp[F[_]: Concurrent: ContextShift](
    blocker: Blocker,
    sink: Pipe[F, Batch, Unit],
    port: Int = agentPort,
  ): Resource[F, Stream[F, Unit]] =
    for {
      avroSchema <- Resource.liftF(AvroInstances.batchSchema[F])
      address <- Resource.liftF(Sync[F].delay(new InetSocketAddress(port)))
      socketGroup <- UDPSocketGroup[F](blocker)
      socket <- socketGroup.open(address)
    } yield socket.reads().map(_.bytes).evalMap(decode[F](avroSchema)).through(sink)
}
