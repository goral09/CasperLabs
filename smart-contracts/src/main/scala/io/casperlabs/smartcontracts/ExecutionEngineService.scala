package io.casperlabs.smartcontracts

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import cats.effect.{Resource, Sync}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.apply._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.Bond
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService.Stub
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.{Epoll, EpollDomainSocketChannel, EpollEventLoopGroup}
import io.netty.channel.kqueue.{KQueueDomainSocketChannel, KQueueEventLoopGroup}
import io.netty.channel.unix.DomainSocketAddress
import monix.eval.{Task, TaskLift}
import simulacrum.typeclass

import scala.util.Either

@typeclass trait ExecutionEngineService[F[_]] {
  //TODO: should this be effectful?
  def emptyStateHash: ByteString
  def exec(
      prestate: ByteString,
      deploys: Seq[Deploy]
  ): F[Either[Throwable, Seq[DeployResult]]]
  def commit(prestate: ByteString, effects: Seq[TransformEntry]): F[Either[Throwable, ByteString]]
  def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]]
  def setBonds(bonds: Map[Array[Byte], Long])
  def query(state: ByteString, baseKey: Key, path: Seq[String]): F[Either[Throwable, Value]]
}

class GrpcExecutionEngineService[F[_]: Sync: Log: TaskLift] private[smartcontracts] (
    addr: Path,
    maxMessageSize: Int,
    stub: Stub
) extends ExecutionEngineService[F] {

  var initialBonds = Seq.empty[Bond]

  override def emptyStateHash: ByteString = ByteString.copyFrom(Array.fill(32)(0.toByte))

  def sendMessage[A, B, R](msg: A, rpc: Stub => A => Task[B])(f: B => R): F[R] =
    for {
      response <- rpc(stub)(msg).to[F]
    } yield f(response)

  override def exec(
      prestate: ByteString,
      deploys: Seq[Deploy]
  ): F[Either[Throwable, Seq[DeployResult]]] =
    sendMessage(ExecRequest(prestate, deploys), _.exec) {
      _.result match {
        case ExecResponse.Result.Success(ExecResult(deployResults)) =>
          Right(deployResults)
        //TODO: Capture errors better than just as a string
        case ExecResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
        case ExecResponse.Result.MissingParent(RootNotFound(missing)) =>
          Left(
            new SmartContractEngineError(s"Missing states: ${Base16.encode(missing.toByteArray)}")
          )
      }
    }

  override def commit(
      prestate: ByteString,
      effects: Seq[TransformEntry]
  ): F[Either[Throwable, ByteString]] =
    sendMessage(CommitRequest(prestate, effects), _.commit) {
      _.result match {
        case CommitResponse.Result.Success(CommitResult(poststateHash)) =>
          Right(poststateHash)
        case CommitResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
        case CommitResponse.Result.MissingPrestate(RootNotFound(hash)) =>
          Left(new SmartContractEngineError(s"Missing pre-state: $hash"))
        case CommitResponse.Result.FailedTransform(PostEffectsError(message)) =>
          Left(new SmartContractEngineError(s"Error executing transform: $message"))
      }
    }

  override def query(
      state: ByteString,
      baseKey: Key,
      path: Seq[String]
  ): F[Either[Throwable, Value]] =
    sendMessage(QueryRequest(state, Some(baseKey), path), _.query) {
      _.result match {
        case QueryResponse.Result.Success(value) => Right(value)
        case QueryResponse.Result.Empty          => Left(new SmartContractEngineError("empty response"))
        case QueryResponse.Result.Failure(err)   => Left(new SmartContractEngineError(err))
      }
    }
  // Todo
  override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
    // FIXME: Implement bonds!
    initialBonds.pure[F]

  // Todo Pass in the genesis bonds until we have a solution based on the BlockStore.
  override def setBonds(bonds: Map[Array[Byte], Long]): Unit =
    initialBonds = bonds.map {
      case (validator, weight) => Bond(ByteString.copyFrom(validator), weight)
    }.toSeq
}

object ExecutionEngineService {
  type Stub = IpcGrpcMonix.ExecutionEngineServiceStub

  def noOpApi[F[_]: Applicative](): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      override def emptyStateHash: ByteString = ByteString.EMPTY
      override def exec(
          prestate: ByteString,
          deploys: Seq[Deploy]
      ): F[Either[Throwable, Seq[DeployResult]]] =
        Seq.empty[DeployResult].asRight[Throwable].pure
      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = ByteString.EMPTY.asRight[Throwable].pure
      override def query(
          state: ByteString,
          baseKey: Key,
          path: Seq[String]
      ): F[Either[Throwable, Value]] =
        Applicative[F]
          .pure[Either[Throwable, Value]](Left(new SmartContractEngineError("unimplemented")))
      override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
        Seq.empty[Bond].pure
      override def setBonds(bonds: Map[Array[Byte], Long]): Unit = ()
    }
}

object GrpcExecutionEngineService {
  def apply[F[_]: Sync: Log: TaskLift](
      addr: Path,
      maxMessageSize: Int
  ): Resource[F, GrpcExecutionEngineService[F]] =
    new ExecutionEngineConf[F](addr, maxMessageSize).apply
}
