package io.janstenpickle.trace4cats.newrelic

import cats.effect.{Blocker, Concurrent, ConcurrentEffect, Resource, Timer}
import fs2.Chunk
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.trace4cats.`export`.{CompleterConfig, QueuedSpanCompleter}
import io.janstenpickle.trace4cats.kernel.SpanCompleter
import io.janstenpickle.trace4cats.model.TraceProcess
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

object NewRelicSpanCompleter {
  def blazeClient[F[_]: ConcurrentEffect: Timer](
    blocker: Blocker,
    process: TraceProcess,
    apiKey: String,
    endpoint: Endpoint,
    config: CompleterConfig = CompleterConfig(),
  ): Resource[F, SpanCompleter[F]] =
    BlazeClientBuilder[F](blocker.blockingContext).resource
      .flatMap(apply[F](_, process, apiKey, endpoint, config))

  def apply[F[_]: Concurrent: Timer](
    client: Client[F],
    process: TraceProcess,
    apiKey: String,
    endpoint: Endpoint,
    config: CompleterConfig = CompleterConfig(),
  ): Resource[F, SpanCompleter[F]] =
    for {
      implicit0(logger: Logger[F]) <- Resource.liftF(Slf4jLogger.create[F])
      exporter <- Resource.liftF(NewRelicSpanExporter[F, Chunk](client, apiKey, endpoint))
      completer <- QueuedSpanCompleter[F](process, exporter, config)
    } yield completer
}
