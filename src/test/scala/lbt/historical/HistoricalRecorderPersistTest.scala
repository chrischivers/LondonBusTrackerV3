package lbt.historical

import akka.actor.Kill
import lbt.StandardTestFixture
import lbt.datasource.SourceLine
import lbt.datasource.streaming.DataStreamProcessor
import org.scalatest.Matchers._
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import scala.concurrent.duration._
import scala.util.Random

class HistoricalRecorderPersistTest extends fixture.FunSuite with ScalaFutures with Eventually {

  type FixtureParam = StandardTestFixture

  override implicit val patienceConfig = PatienceConfig(
    timeout = scaled(30 seconds),
    interval = scaled(1 second)
  )

  override def withFixture(test: OneArgTest) = {
    val fixture = new StandardTestFixture
    try test(fixture)
    finally {
      fixture.actorSystem.terminate().futureValue
      fixture.testDefinitionsTable.deleteTable
      fixture.testHistoricalTable.deleteTable
      Thread.sleep(1000)
    }
  }

    test("Vehicle actors should persist record if stop arrival time information is complete and passes validation"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testConfig = f.testDataSourceConfig.copy(simulationIterator = Some(testLines.toIterator))
    val dataStreamProcessorTest = new DataStreamProcessor(testConfig, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }

    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      dataStreamProcessorTest.numberLinesProcessed.futureValue shouldBe testLines.size
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get().toInt shouldBe testLines.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get().toInt shouldBe testLines.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 1
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.busRoute == f.testBusRoute1) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.size shouldBe testLines.size
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.map(record => record.stopID) shouldEqual routeDefFromDb.map(stop => stop.stopID)
      f.vehicleActorSupervisor.getValidationErrorMap.futureValue.size shouldBe 0
    }
   // dataStreamProcessorTest.stop
  }

  test("Persisted record should be loaded with stop sequence in same order and with same values"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testConfig = f.testDataSourceConfig.copy(simulationIterator = Some(testLines.toIterator))
    val dataStreamProcessorTest = new DataStreamProcessor(testConfig, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }
    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      dataStreamProcessorTest.numberLinesProcessed.futureValue shouldBe testLines.size
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get() shouldBe testLines.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get() shouldBe testLines.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 1
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.busRoute == f.testBusRoute1) shouldBe true
      val historicalRecord = f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head
      historicalRecord.stopRecords.map(record => record.stopID) shouldEqual routeDefFromDb.map(stop => stop.stopID)
    }
    dataStreamProcessorTest.stop
  }


  test("Vehicle actors should not persist record if there is a gap in the sequence"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val splitTestLines = testLines.splitAt(testLines.size / 2)
    val testLinesWithMissing = splitTestLines._1 ++ splitTestLines._2.tail

    val testConfig = f.testDataSourceConfig.copy(simulationIterator = Some(testLinesWithMissing.toIterator))
    val dataStreamProcessorTest = new DataStreamProcessor(testConfig, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }
    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      dataStreamProcessorTest.numberLinesProcessed.futureValue shouldBe testLinesWithMissing.size
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get() shouldBe testLinesWithMissing.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get() shouldBe testLinesWithMissing.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 0
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe false
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.busRoute == f.testBusRoute1) shouldBe false
      f.vehicleActorSupervisor.getValidationErrorMap.futureValue.size shouldBe 1
      f.vehicleActorSupervisor.getValidationErrorMap.futureValue.get(f.testBusRoute1) should be (defined)
    }
    dataStreamProcessorTest.stop
  }

  ignore("Vehicle actors should not persist record if the number of stops received falls below the minimum required"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testLinesLast4 = testLines.takeRight(4)

    val testConfig = f.testDataSourceConfig.copy(simulationIterator = Some(testLinesLast4.toIterator))
    val dataStreamProcessorTest = new DataStreamProcessor(testConfig, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }
    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      dataStreamProcessorTest.numberLinesProcessed.futureValue shouldBe testLinesLast4.size
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get() shouldBe testLinesLast4.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get() shouldBe testLinesLast4.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 0
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe false
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.busRoute == f.testBusRoute1) shouldBe false
      f.vehicleActorSupervisor.getValidationErrorMap.futureValue.size shouldBe 1
      f.vehicleActorSupervisor.getValidationErrorMap.futureValue.get(f.testBusRoute1) should be (defined)
    }
    dataStreamProcessorTest.stop
  }

  test("Vehicle actors should persist record if there is a gap at the beginning of the sequence (bus started midway through route)"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testLinesSecondHalf = testLines.splitAt(testLines.size / 2)._2

    val testConfig = f.testDataSourceConfig.copy(simulationIterator = Some(testLinesSecondHalf.toIterator))
    val dataStreamProcessorTest = new DataStreamProcessor(testConfig, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }

    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      dataStreamProcessorTest.numberLinesProcessed.futureValue shouldBe testLinesSecondHalf.size
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get() shouldBe testLinesSecondHalf.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get() shouldBe testLinesSecondHalf.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 1
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.busRoute == f.testBusRoute1) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.count(result => result.journey.busRoute == f.testBusRoute1) shouldBe 1
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.size shouldBe testLinesSecondHalf.size
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.map(record => record.stopID) shouldEqual routeDefFromDb.map(stop => stop.stopID).splitAt(routeDefFromDb.size / 2)._2
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.map(record => routeDefFromDb.indexWhere(x => x.stopID == record.stopID) + 1) shouldEqual f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.map(record => record.seqNo)
    }
    dataStreamProcessorTest.stop
  }

  test("Multiple records should be persisted where the same bus on the same route makes the same journey after period of inactivity"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines1: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testConfig1 = f.testDataSourceConfig.copy(simulationIterator = Some(testLines1.toIterator))
    val dataStreamProcessorTest1 = new DataStreamProcessor(testConfig1, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest1.start

    Thread.sleep(7000)

    f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
    Thread.sleep(1000)
    dataStreamProcessorTest1.stop
    dataStreamProcessorTest1.processorControllerActor ! Kill
    Thread.sleep(1000)

    val testLines2: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testConfig2 = f.testDataSourceConfig.copy(simulationIterator = Some(testLines2.toIterator))
    val dataStreamProcessorTest2 = new DataStreamProcessor(testConfig2, f.historicalSourceLineProcessor, "dataStreamProcessingController-2")(f.actorSystem, f.executionContext)

    dataStreamProcessorTest2.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }
    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get() shouldBe testLines1.size + testLines2.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get() shouldBe testLines1.size + testLines2.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 2
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.count(result => result.journey.busRoute == f.testBusRoute1) shouldBe 2
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.size shouldBe testLines1.size
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg)(1).stopRecords.size shouldBe testLines2.size
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.map(record => record.stopID) shouldEqual routeDefFromDb.map(stop => stop.stopID)
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg)(1).stopRecords.map(record => record.stopID) shouldEqual routeDefFromDb.map(stop => stop.stopID)
    }
    dataStreamProcessorTest2.stop

  }

  test("Only one record should be persisted if multiple persists are requested for the same bus making the same route at the same time"){f=>
    val routeDefFromDb = f.definitions(f.testBusRoute1)
    val vehicleReg = "V123456"

    val testLines1: List[String] = routeDefFromDb.map(busStop =>
      "[1,\"" + busStop.stopID + "\",\"" + f.testBusRoute1.name + "\",1,\"Any Place 1\",\"" + vehicleReg + "\"," + f.generateArrivalTime + "]")

    val testLines2: List[String] = testLines1.map(line => line.replace("Any Place 1", "Any Place 2"))

    val testLinesDoubled = testLines1 ++ testLines2

    val testConfig = f.testDataSourceConfig.copy(simulationIterator = Some(testLinesDoubled.toIterator))
    val dataStreamProcessorTest = new DataStreamProcessor(testConfig, f.historicalSourceLineProcessor)(f.actorSystem, f.executionContext)

    dataStreamProcessorTest.start

    eventually {
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 1
    }

    eventually {
      f.vehicleActorSupervisor.persistAndRemoveInactiveVehicles
      dataStreamProcessorTest.numberLinesProcessed.futureValue shouldBe testLinesDoubled.size
      f.historicalSourceLineProcessor.numberSourceLinesProcessed.get() shouldBe testLinesDoubled.size
      f.historicalSourceLineProcessor.numberSourceLinesValidated.get() shouldBe testLinesDoubled.size
      f.vehicleActorSupervisor.getCurrentActors.futureValue.size shouldBe 0
      f.testHistoricalTable.historicalDBController.numberInsertsRequested.get() shouldBe 1
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.exists(result => result.journey.vehicleReg == vehicleReg) shouldBe true
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.count(result => result.journey.busRoute == f.testBusRoute1) shouldBe 1
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.size shouldBe testLines1.size
      f.testHistoricalTable.getHistoricalRecordFromDbByBusRoute(f.testBusRoute1).futureValue.filter(result => result.journey.vehicleReg == vehicleReg).head.stopRecords.map(record => record.stopID) shouldEqual routeDefFromDb.map(stop => stop.stopID)
    }
    dataStreamProcessorTest.stop
  }



  def sourceLineBackToLine(sourceLine: SourceLine): String = {
    "[1,\"" + sourceLine.stopID + "\",\"" + sourceLine.route + "\"," + sourceLine.direction + ",\"" + sourceLine.destinationText + "\",\"" + sourceLine.vehicleID + "\"," + sourceLine.arrival_TimeStamp + "]"
  }
  def validatedSourceLineBackToLine(sourceLine: ValidatedSourceLine): String = {
    def directionToInt(direction: String): Int = direction match {
      case "outbound" => 1
      case "inbound" => 2
    }
    "[1,\"" + sourceLine.busStop.stopID + "\",\"" + sourceLine.busRoute.name + "\"," + directionToInt(sourceLine.busRoute.direction) + ",\"" + sourceLine.destinationText + "\",\"" + sourceLine.vehicleReg + "\"," + sourceLine.arrival_TimeStamp + "]"
  }
}
