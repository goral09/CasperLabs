package io.casperlabs.casper.util.execengine

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.models.BlockMetadata
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

class ExecEngineUtilTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {

  implicit val logEff = new LogStub[Task]
  implicit val absId  = ToAbstractContext.idToAbstractContext

  implicit val executionEngineService = HashSetCasperTestNode.simpleEEApi[Task]()

  "computeBlockCheckpoint" should "compute the final post-state of a chain properly" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val genesisDeploysCost =
        genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b1Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b1DeploysCost = b1Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b2Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b2DeploysCost = b2Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b3Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b3DeploysCost = b3Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      /*
       * DAG Looks like this:
       *
       *          b3
       *           |
       *          b2
       *           |
       *          b1
       *           |
       *         genesis
       */

      for {
        genesis                                     <- createBlock[Task](Seq.empty, deploys = genesisDeploysCost)
        b1                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysCost)
        b2                                          <- createBlock[Task](Seq(b1.blockHash), deploys = b2DeploysCost)
        b3                                          <- createBlock[Task](Seq(b2.blockHash), deploys = b3DeploysCost)
        dag1                                        <- blockDagStorage.getRepresentation
        blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1)
        (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
        _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
        dag2                                        <- blockDagStorage.getRepresentation
        blockCheckpointB1                           <- computeBlockCheckpoint(b1, genesis, dag2)
        (postB1StateHash, postB1ProcessedDeploys)   = blockCheckpointB1
        _                                           <- injectPostStateHash[Task](1, b1, postB1StateHash, postB1ProcessedDeploys)
        dag3                                        <- blockDagStorage.getRepresentation
        blockCheckpointB2 <- computeBlockCheckpoint(
                              b2,
                              genesis,
                              dag3
                            )
        (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
        _                                         <- injectPostStateHash[Task](2, b2, postB2StateHash, postB2ProcessedDeploys)

        dag4 <- blockDagStorage.getRepresentation
        blockCheckpointB4 <- computeBlockCheckpoint(
                              b3,
                              genesis,
                              dag4
                            )
        (postb3StateHash, _) = blockCheckpointB4
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(1)") should be(true)
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          result = b3PostState.contains("@{7}!(7)") should be(true)
      } yield true
  }

  it should "merge histories in case of multiple parents" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val genesisDeploysWithCost =
        genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b1Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b1DeploysWithCost =
        b1Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(2))

      val b2Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b2DeploysWithCost =
        b2Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b3Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b3DeploysWithCost =
        b3Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(5))

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */
      for {
        genesis                                     <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        b3                                          <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
        dag1                                        <- blockDagStorage.getRepresentation
        blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1)
        (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
        _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
        dag2                                        <- blockDagStorage.getRepresentation
        blockCheckpointB1 <- computeBlockCheckpoint(
                              b1,
                              genesis,
                              dag2
                            )
        (postB1StateHash, postB1ProcessedDeploys) = blockCheckpointB1
        _                                         <- injectPostStateHash[Task](1, b1, postB1StateHash, postB1ProcessedDeploys)
        dag3                                      <- blockDagStorage.getRepresentation
        blockCheckpointB2 <- computeBlockCheckpoint(
                              b2,
                              genesis,
                              dag3
                            )
        (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
        _                                         <- injectPostStateHash[Task](2, b2, postB2StateHash, postB2ProcessedDeploys)
        updatedGenesis                            <- blockDagStorage.lookupByIdUnsafe(0)
        dag4                                      <- blockDagStorage.getRepresentation
        blockCheckpointB3 <- computeBlockCheckpoint(
                              b3,
                              updatedGenesis,
                              dag4
                            )
        (postb3StateHash, _) = blockCheckpointB3
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          _      = b3PostState.contains("@{5}!(5)") should be(true)
//          result = b3PostState.contains("@{6}!(6)") should be(true)
      } yield true
  }

  val registry = """ """.stripMargin

  val other = """ """.stripMargin

  def prepareDeploys(v: Vector[ByteString], c: Long) = {
    val genesisDeploys =
      v.map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
    genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(c))
  }

  it should "merge histories in case of multiple parents with complex contract" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val contract = ByteString.copyFromUtf8(registry)

      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1)
      val b1DeploysWithCost      = prepareDeploys(Vector(contract), 2)
      val b2DeploysWithCost      = prepareDeploys(Vector(contract), 1)
      val b3DeploysWithCost      = prepareDeploys(Vector.empty, 5)

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */

      def step(index: Int, genesis: BlockMessage) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
                                         )
          (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
          result <- injectPostStateHash[Task](
                     index,
                     b1,
                     postB1StateHash,
                     postB1ProcessedDeploys
                   )
        } yield result
      for {
        genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        b3      <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
        _       <- step(0, genesis)
        _       <- step(1, genesis)
        _       <- step(2, genesis)
        dag     <- blockDagStorage.getRepresentation
        postState <- validateBlockCheckpoint[Task](
                      b3,
                      dag,
                      (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
                    )
        result = postState shouldBe Right(None)
      } yield result
  }

  it should "merge histories in case of multiple parents (uneven histories)" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val contract = ByteString.copyFromUtf8(registry)

      val genesisDeploysWithCost = prepareDeploys(Vector(contract), 1)

      val b1DeploysWithCost = prepareDeploys(Vector(contract), 2)

      val b2DeploysWithCost = prepareDeploys(Vector(contract), 1)

      val b3DeploysWithCost = prepareDeploys(Vector(contract), 5)

      val b4DeploysWithCost = prepareDeploys(Vector(contract), 5)

      val b5DeploysWithCost = prepareDeploys(Vector(contract), 5)

      /*
       * DAG Looks like this:
       *
       *           b5
       *          /  \
       *         |    |
       *         |    b4
       *         |    |
       *        b2    b3
       *         \    /
       *          \  /
       *           |
       *           b1
       *           |
       *         genesis
       */

      def step(index: Int, genesis: BlockMessage) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
                                         )
          (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
          result <- injectPostStateHash[Task](
                     index,
                     b1,
                     postB1StateHash,
                     postB1ProcessedDeploys
                   )
        } yield result

      for {
        genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[Task](Seq(b1.blockHash), deploys = b2DeploysWithCost)
        b3      <- createBlock[Task](Seq(b1.blockHash), deploys = b3DeploysWithCost)
        b4      <- createBlock[Task](Seq(b3.blockHash), deploys = b4DeploysWithCost)
        b5      <- createBlock[Task](Seq(b2.blockHash, b4.blockHash), deploys = b5DeploysWithCost)
        dag1    <- blockDagStorage.getRepresentation
        computeBlockCheckpointResult <- computeBlockCheckpoint(
                                         genesis,
                                         genesis,
                                         dag1
                                       )
        (postGenStateHash, postGenProcessedDeploys) = computeBlockCheckpointResult
        _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
        _                                           <- step(1, genesis)
        _                                           <- step(2, genesis)
        _                                           <- step(3, genesis)
        _                                           <- step(4, genesis)

        dag2 <- blockDagStorage.getRepresentation
        postState <- validateBlockCheckpoint[Task](
                      b5,
                      dag2,
                      (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
                    )
//        Result should be validated post - state - hash.
        result = postState shouldBe Right(None)
      } yield result
  }

  def computeSingleProcessedDeploy[F[_]: ExecutionEngineService](
      dag: BlockDagRepresentation[Task],
      deploy: DeployData*
  )(implicit blockStore: BlockStore[Task]): Task[Seq[ProcessedDeploy]] =
    for {
      computeResult <- ExecEngineUtil
                        .computeDeploysCheckpoint[Task](
                          Seq.empty,
                          deploy,
                          dag,
                          (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
                        )
      (_, _, result, _) = computeResult
    } yield result

  "computeDeploysCheckpoint" should "aggregate the result of deploying multiple programs within the block" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        // reference costs
        // deploy each Rholang program separately and record its cost
        val deploy1 = ProtoUtil.sourceDeploy(
          ByteString.copyFromUtf8("@1!(Nil)"),
          System.currentTimeMillis(),
          Integer.MAX_VALUE
        )
        val deploy2 =
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("@3!([1,2,3,4])"),
            System.currentTimeMillis(),
            Integer.MAX_VALUE
          )
        val deploy3 =
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("for(@x <- @0) { @4!(x.toByteArray()) }"),
            System.currentTimeMillis(),
            Integer.MAX_VALUE
          )
        for {
          dag           <- blockDagStorage.getRepresentation
          proc1         <- computeSingleProcessedDeploy(dag, deploy1)
          proc2         <- computeSingleProcessedDeploy(dag, deploy2)
          proc3         <- computeSingleProcessedDeploy(dag, deploy3)
          singleResults = proc1 ++ proc2 ++ proc3
          batchDeploy   = Seq(deploy1, deploy2, deploy3)
          batchResult   <- computeSingleProcessedDeploy(dag, batchDeploy: _*)
        } yield batchResult should contain theSameElementsAs singleResults
  }

  "validateBlockCheckpoint" should "not return a checkpoint for an invalid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys = Vector(ByteString.EMPTY)
        .map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val processedDeploys = deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))
      val invalidHash      = ByteString.copyFromUtf8("invalid")
      for {
        block <- createBlock[Task](Seq.empty, deploys = processedDeploys, tsHash = invalidHash)
        dag   <- blockDagStorage.getRepresentation
        validateResult <- validateBlockCheckpoint[Task](
                           block,
                           dag,
                           (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
                         )
        Right(stateHash) = validateResult
      } yield stateHash should be(None)
  }

  it should "return a checkpoint with the right hash for a valid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys =
        Vector(
          ByteString.EMPTY
        ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))

      for {
        dag1 <- blockDagStorage.getRepresentation
        deploysCheckpoint <- ExecEngineUtil.computeDeploysCheckpoint[Task](
                              Seq.empty,
                              deploys,
                              dag1,
                              (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
                            )
        (preStateHash, computedTsHash, processedDeploys, _) = deploysCheckpoint
        block <- createBlock[Task](
                  Seq.empty,
                  deploys = processedDeploys,
                  tsHash = computedTsHash,
                  preStateHash = preStateHash
                )
        dag2 <- blockDagStorage.getRepresentation

        validateResult <- validateBlockCheckpoint[Task](
                           block,
                           dag2,
                           (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
                         )
        Right(tsHash) = validateResult
      } yield tsHash should be(Some(computedTsHash))
  }

  "findMultiParentsBlockHashesForReplay" should "filter out duplicate ancestors of main parent block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1L)
      val b1DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)
      val b2DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)
      val b3DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */

      def step(index: Int, genesis: BlockMessage) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
                                         )
          (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
          result <- injectPostStateHash[Task](
                     index,
                     b1,
                     postB1StateHash,
                     postB1ProcessedDeploys
                   )
        } yield result
      for {
        genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        b3      <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
        _       <- step(0, genesis)
        _       <- step(1, genesis)
        _       <- step(2, genesis)
        dag     <- blockDagStorage.getRepresentation
        blockHashes <- ExecEngineUtil.blocksToApply(
                        Seq(b1, b2),
                        dag
                      )
        _ = withClue("Main parent hasn't been filtered out: ") { blockHashes.size shouldBe (1) }

      } yield ()
  }

}
