package io.casperlabs.casper.util

import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.{Blake2b256, Keccak256}
import io.casperlabs.crypto.signatures.{Ed25519, Secp256k1}
import io.casperlabs.shared.PathOps.RichPath
import java.io.PrintWriter
import java.nio.file.{Files, Path}

import cats.Id
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler

object BondingUtil {
  def bondingForwarderAddress(ethAddress: String): String = s"${ethAddress}_bondingForwarder"
  def bondingStatusOut(ethAddress: String): String        = s"${ethAddress}_bondingOut"
  def transferStatusOut(ethAddress: String): String       = s"${ethAddress}_transferOut"

  def bondingForwarderDeploy(bondKey: String, ethAddress: String): String = """"""

  def unlockDeploy[F[_]: Sync](ethAddress: String, pubKey: String, secKey: String)(
      implicit scheduler: Scheduler
  ): F[String] =
    preWalletUnlockDeploy(ethAddress, pubKey, Base16.decode(secKey), s"${ethAddress}_unlockOut")

  def issuanceBondDeploy[F[_]: Sync](
      amount: Long,
      ethAddress: String,
      pubKey: String,
      secKey: String
  )(
      implicit scheduler: Scheduler
  ): F[String] =
    issuanceWalletTransferDeploy(
      0, //nonce
      amount,
      bondingForwarderAddress(ethAddress),
      transferStatusOut(ethAddress),
      pubKey,
      Base16.decode(secKey)
    )

  def preWalletUnlockDeploy[F[_]: Sync](
      ethAddress: String,
      pubKey: String,
      secKey: Array[Byte],
      statusOut: String
  )(implicit scheduler: Scheduler): F[String] = ???

  def walletTransferSigData[F[_]: Sync](
      nonce: Int,
      amount: Long,
      destination: String
  )(implicit scheduler: Scheduler): F[Array[Byte]] = ???

  def issuanceWalletTransferDeploy[F[_]: Sync](
      nonce: Int,
      amount: Long,
      destination: String,
      transferStatusOut: String,
      pubKey: String,
      secKey: Array[Byte]
  )(implicit scheduler: Scheduler): F[String] = """""".pure[F]

  def faucetBondDeploy[F[_]: Sync](
      amount: Long,
      sigAlgorithm: String,
      pubKey: String,
      secKey: Array[Byte]
  ): F[String] = """""".pure[F]

  def writeFile[F[_]: Sync](name: String, content: String): F[Unit] = {
    val file =
      Resource.make[F, PrintWriter](Sync[F].delay { new PrintWriter(name) })(
        pw => Sync[F].delay { pw.close() }
      )
    file.use(pw => Sync[F].delay { pw.println(content) })
  }

  def makeRuntimeDir[F[_]: Sync]: Resource[F, Path] =
    Resource.make[F, Path](Sync[F].delay { Files.createTempDirectory("casper-bonding-helper-") })(
      runtimeDir => Sync[F].delay { runtimeDir.recursivelyDelete() }
    )

  def makeExecutionEngineServiceResource[F[_]: Sync](
      runtimeDirResource: Resource[F, Path]
  ): Resource[F, ExecutionEngineService[Task]] = ???

  def bondingDeploy[F[_]: Sync](
      bondKey: String,
      ethAddress: String,
      amount: Long,
      secKey: String,
      pubKey: String
  ): F[Unit] = ???

  def writeFaucetBasedRhoFiles[F[_]: Sync](
      amount: Long,
      sigAlgorithm: String,
      secKey: String,
      pubKey: String
  ): F[Unit] = ???

}
