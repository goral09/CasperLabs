package io.casperlabs.casper.api

import scala.concurrent.duration._
import cats.Monad
import cats.data.EitherT
import cats.effect.concurrent.Semaphore
import cats.implicits._
import io.casperlabs.casper._
import io.casperlabs.casper.helper.HashSetCasperTestNode
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.rholang._
import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.MultiParentCasper.ignoreDoppelgangerCheck
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.shared.Time
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockDagRepresentation
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Matchers}

class CreateBlockAPITest extends FlatSpec with Matchers {
  import HashSetCasperTest._
  import HashSetCasperTestNode.Effect

  private implicit val scheduler: Scheduler = Scheduler.fixedPool("create-block-api-test", 4)
  implicit val metrics                      = new Metrics.MetricsNOP[Task]

  private val (validatorKeys, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  private val bonds                       = createBonds(validators)
  private val genesis                     = createGenesis(bonds)

  "createBlock" should "not allow simultaneous calls" in {
    implicit val scheduler = Scheduler.fixedPool("three-threads", 3)
    implicit val time = new Time[Task] {
      private val timer                               = Task.timer
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
    }
    val node   = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    val casper = new SleepingMultiParentCasperImpl[Effect](node.casperEff)
    val deploys = List(
      "@0!(0) | for(_ <- @0){ @1!(1) }",
      "for(_ <- @1){ @2!(2) }"
    ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))

    implicit val logEff = new LogStub[Effect]
    def testProgram(blockApiLock: Semaphore[Effect])(
        implicit casperRef: MultiParentCasperRef[Effect]
    ): Effect[(DeployServiceResponse, DeployServiceResponse)] = EitherT.liftF(
      for {
        t1 <- (BlockAPI.deploy[Effect](deploys.head) *> BlockAPI
               .createBlock[Effect](blockApiLock)).value.start
        _ <- Time[Task].sleep(2.second)
        t2 <- (BlockAPI.deploy[Effect](deploys.last) *> BlockAPI
               .createBlock[Effect](blockApiLock)).value.start //should fail because other not done
        r1 <- t1.join
        r2 <- t2.join
      } yield (r1.right.get, r2.right.get)
    )

    val (response1, response2) = (for {
      casperRef    <- MultiParentCasperRef.of[Effect]
      _            <- casperRef.set(casper)
      blockApiLock <- Semaphore[Effect](1)
      result       <- testProgram(blockApiLock)(casperRef)
    } yield result).value.unsafeRunSync.right.get

    response1.success shouldBe true
    response2.success shouldBe false
    response2.message shouldBe "Error: There is another propose in progress."

    node.tearDown()
  }
}

private class SleepingMultiParentCasperImpl[F[_]: Monad: Time](underlying: MultiParentCasper[F])
    extends MultiParentCasper[F] {

  def addBlock(
      b: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  ): F[BlockStatus]                                     = underlying.addBlock(b, ignoreDoppelgangerCheck[F])
  def contains(b: BlockMessage): F[Boolean]             = underlying.contains(b)
  def deploy(d: DeployData): F[Either[Throwable, Unit]] = underlying.deploy(d)
  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockMessage]] =
    underlying.estimator(dag)
  def blockDag: F[BlockDagRepresentation[F]] = underlying.blockDag
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    underlying.normalizedInitialFault(weights)
  def lastFinalizedBlock: F[BlockMessage]          = underlying.lastFinalizedBlock
  def storageContents(hash: ByteString): F[String] = underlying.storageContents(hash)
  def fetchDependencies: F[Unit]                   = underlying.fetchDependencies

  override def createBlock: F[CreateBlockStatus] =
    for {
      result <- underlying.createBlock
      _      <- implicitly[Time[F]].sleep(5.seconds)
    } yield result

}
