package io.casperlabs.casper.util

import ProtoUtil._
import com.google.protobuf.ByteString
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.catscontrib._
import cats.implicits._
import io.casperlabs.casper.helper.{BlockDagStorageFixture, BlockGenerator}
import cats.data._
import cats.effect.Bracket
import cats.implicits._
import cats.mtl.MonadState
import cats.mtl.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.helper.BlockGenerator
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.scalatestcontrib._
import monix.eval.Task
import io.casperlabs.casper.util.rholang.Resources.mkRuntimeManager
import io.casperlabs.casper.util.rholang.{InterpreterUtil, ProcessedDeployUtil, RuntimeManager}
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.{HashMap, HashSet}

class CasperUtilTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {

  implicit val logEff                  = new LogStub[Task]()
  implicit val casperSmartContractsApi = ExecutionEngineService.noOpApi[Task]()

  "isInMainChain" should "classify appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        genesis <- createBlock[Task](Seq())
        b2      <- createBlock[Task](Seq(genesis.blockHash))
        b3      <- createBlock[Task](Seq(b2.blockHash))

        dag <- blockDagStorage.getRepresentation

        _      <- isInMainChain(dag, genesis.blockHash, b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, b2.blockHash, b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, b3.blockHash, b2.blockHash) shouldBeF false
        result <- isInMainChain(dag, b3.blockHash, genesis.blockHash) shouldBeF false
      } yield result
  }

  "isInMainChain" should "classify diamond DAGs appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        genesis <- createBlock[Task](Seq())
        b2      <- createBlock[Task](Seq(genesis.blockHash))
        b3      <- createBlock[Task](Seq(genesis.blockHash))
        b4      <- createBlock[Task](Seq(b2.blockHash, b3.blockHash))

        dag <- blockDagStorage.getRepresentation

        _      <- isInMainChain(dag, genesis.blockHash, b2.blockHash) shouldBeF true
        _      <- isInMainChain(dag, genesis.blockHash, b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, genesis.blockHash, b4.blockHash) shouldBeF true
        _      <- isInMainChain(dag, b2.blockHash, b4.blockHash) shouldBeF true
        result <- isInMainChain(dag, b3.blockHash, b4.blockHash) shouldBeF false
      } yield result
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "isInMainChain" should "classify complicated chains appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1 = generateValidator("Validator One")
      val v2 = generateValidator("Validator Two")

      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
        b2      <- createBlock[Task](Seq(genesis.blockHash), v2)
        b3      <- createBlock[Task](Seq(genesis.blockHash), v1)
        b4      <- createBlock[Task](Seq(b2.blockHash), v2)
        b5      <- createBlock[Task](Seq(b2.blockHash), v1)
        b6      <- createBlock[Task](Seq(b4.blockHash), v2)
        b7      <- createBlock[Task](Seq(b4.blockHash), v1)
        b8      <- createBlock[Task](Seq(b7.blockHash), v1)

        dag <- blockDagStorage.getRepresentation

        _      <- isInMainChain(dag, genesis.blockHash, b2.blockHash) shouldBeF true
        _      <- isInMainChain(dag, b2.blockHash, b3.blockHash) shouldBeF false
        _      <- isInMainChain(dag, b3.blockHash, b4.blockHash) shouldBeF false
        _      <- isInMainChain(dag, b4.blockHash, b5.blockHash) shouldBeF false
        _      <- isInMainChain(dag, b5.blockHash, b6.blockHash) shouldBeF false
        _      <- isInMainChain(dag, b6.blockHash, b7.blockHash) shouldBeF false
        _      <- isInMainChain(dag, b7.blockHash, b8.blockHash) shouldBeF true
        _      <- isInMainChain(dag, b2.blockHash, b6.blockHash) shouldBeF true
        _      <- isInMainChain(dag, b2.blockHash, b8.blockHash) shouldBeF true
        result <- isInMainChain(dag, b4.blockHash, b2.blockHash) shouldBeF false
      } yield result
  }

  /*
   * DAG Looks like this:
   *
   *       b9      b10
   *        \      /
   *        b7   b8
   *          \  /
   *           b6
   *           / \
   *      b4  /   \  b5
   *       | /     \ |
   *       b2       b3
   *        \       /
   *         genesis
   */
  "Blocks" should "conflict if they use the same deploys in different histories" ignore withStorage {
    //TODO: replace runtimemanager usage with new execengineutil
    implicit blockStore => implicit blockDagStorage =>
      for {
        deploys <- (0 until 6).toList.traverse(basicProcessedDeploy[Task])
        genesis <- createBlock[Task](Seq())
        b2      <- createBlock[Task](Seq(genesis.blockHash), deploys = Seq(deploys(0)))
        b3      <- createBlock[Task](Seq(genesis.blockHash), deploys = Seq(deploys(1)))
        b4      <- createBlock[Task](Seq(b2.blockHash), deploys = Seq(deploys(2)))
        b5      <- createBlock[Task](Seq(b3.blockHash), deploys = Seq(deploys(2)))
        b6      <- createBlock[Task](Seq(b2.blockHash, b3.blockHash), deploys = Seq(deploys(2)))
        b7      <- createBlock[Task](Seq(b6.blockHash), deploys = Seq(deploys(3)))
        b8      <- createBlock[Task](Seq(b6.blockHash), deploys = Seq(deploys(5)))
        b9      <- createBlock[Task](Seq(b7.blockHash), deploys = Seq(deploys(5)))
        b10     <- createBlock[Task](Seq(b8.blockHash), deploys = Seq(deploys(4)))

        dag <- blockDagStorage.getRepresentation
        result <- mkRuntimeManager("casper-util-test").use { runtimeManager =>
                   for {
                     computeBlockCheckpointResult <- computeBlockCheckpoint(
                                                      genesis,
                                                      genesis,
                                                      dag
                                                    )
                     (postGenStateHash, postGenProcessedDeploys) = computeBlockCheckpointResult
                     _ <- injectPostStateHash[Task](
                           0,
                           genesis,
                           postGenStateHash,
                           postGenProcessedDeploys
                         )
                     _ <- updateChainWithBlockStateUpdate[Task](1, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](2, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](3, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](4, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](5, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](6, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](7, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](8, genesis)
                     _ <- updateChainWithBlockStateUpdate[Task](9, genesis)

                     _      <- conflicts[Task](b2, b3, genesis, dag) shouldBeF false
                     _      <- conflicts[Task](b4, b5, genesis, dag) shouldBeF true
                     _      <- conflicts[Task](b6, b6, genesis, dag) shouldBeF false
                     _      <- conflicts[Task](b6, b9, genesis, dag) shouldBeF false
                     _      <- conflicts[Task](b7, b8, genesis, dag) shouldBeF false
                     _      <- conflicts[Task](b7, b10, genesis, dag) shouldBeF false
                     result <- conflicts[Task](b9, b10, genesis, dag) shouldBeF true
                   } yield result
                 }
      } yield result
  }
}
