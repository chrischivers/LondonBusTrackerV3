package lbt.historical

import akka.actor.Actor
import com.typesafe.scalalogging.StrictLogging
import lbt.HistoricalRecordsConfig
import lbt.comon._
import lbt.database.definitions.BusDefinitionsTable
import lbt.database.historical.HistoricalTable

import scalaz.Scalaz._
import scalaz._

case class StopDataRecordToPersist(seqNo: Int, busStopId: String, arrivalTime: Long)
case class RecordedVehicleDataToPersist(vehicleReg: String, busRoute: BusRoute, stopArrivalRecords: List[StopDataRecordToPersist])

class VehicleActor(vehicleActorID: VehicleActorID, historicalRecordsConfig: HistoricalRecordsConfig, busDefinitionsTable: BusDefinitionsTable, historicalTable: HistoricalTable) extends Actor with StrictLogging {
  val name: String = self.path.name
  type StringValidation[T] = ValidationNel[String, T]

  def receive: Receive = active(None, Map.empty, List.empty, System.currentTimeMillis())

  def active(route: Option[BusRoute],
             stopArrivalRecords: Map[BusStop, Long],
             busStopDefinitionList: List[BusStop],
             lastUpdatedTime: Long
            ): Receive = {

    case vsl: ValidatedSourceLine =>
      assert(vsl.vehicleReg + "-" + vsl.busRoute.name + "-" + vsl.busRoute.direction == name)

      if (route.isEmpty) {
        val stopDefinitionList = busDefinitionsTable.getBusRouteDefinitions().apply(vsl.busRoute)
        context.become(active(Some(vsl.busRoute), stopArrivalRecords + (vsl.busStop -> vsl.arrival_TimeStamp), stopDefinitionList, System.currentTimeMillis()))
      } else {
        val stopArrivalRecordsWithLastStop = stopArrivalRecords + (vsl.busStop -> vsl.arrival_TimeStamp)
        context.become(active(Some(vsl.busRoute), stopArrivalRecordsWithLastStop, busStopDefinitionList, System.currentTimeMillis()))
      }
    case GetArrivalRecords(_) => sender ! stopArrivalRecords

    case PersistToDB =>
      validateBeforePersist(route.get, stopArrivalRecords, busStopDefinitionList, lastUpdatedTime) match {
        case Success(completeList) =>
          logger.info(s"Persisting data for vehicle $name to DB")
          historicalTable.insertHistoricalRecordIntoDB(RecordedVehicleDataToPersist(vehicleActorID.vehicleReg, route.get, completeList))
        case Failure(e) => //logger.info(s"Failed validation before persisting. Stop Arrival Records. Error: $e. \n Stop Arrival Records: $stopArrivalRecords.")
      }
  }

  def validateBeforePersist(route: BusRoute, stopArrivalRecords: Map[BusStop, Long], busStopDefinitionList: List[BusStop], vehicleLastUpdated: Long): StringValidation[List[StopDataRecordToPersist]] = {

    val orderedStopsList: List[(Int, BusStop, Option[Long])] = busStopDefinitionList.zipWithIndex.map{case(stop, index) => (index, stop, stopArrivalRecords.get(stop))}

    val orderedStopListWithFutureTimesRemoved: List[(Int, BusStop, Option[Long])] = orderedStopsList.map(stop => {
      if(stop._3.isDefined && stop._3.get > vehicleLastUpdated + historicalRecordsConfig.toleranceForFuturePredictions) (stop._1, stop._2, None)
      else stop
    })

    def minimumNumberOfRecordsReceived: StringValidation[Unit] = {
      val numberOfRecordsWithData = orderedStopListWithFutureTimesRemoved.count(rec => rec._3.isDefined)
      if(numberOfRecordsWithData >= historicalRecordsConfig.minimumNumberOfStopsToPersist) ().successNel
      else s"Not enough stops to persist. Number of stops: $numberOfRecordsWithData. Minimum ${historicalRecordsConfig.minimumNumberOfStopsToPersist}".failureNel
    }

    def noGapsInSequence: StringValidation[Unit] = {
      val orderedExisting = orderedStopListWithFutureTimesRemoved.filter(elem => elem._3.isDefined)
      if (orderedExisting.nonEmpty) {
        if (orderedExisting.size == orderedExisting.last._1 - orderedExisting.head._1 + 1) ().successNel
        else s"Gaps encountered in the sequence received (excluding those at beginning and/or end. StopList: $orderedStopListWithFutureTimesRemoved".failureNel
      } else s"No elements in filtered sequence".failureNel
    }

    def stopArrivalTimesAreIncremental: StringValidation[Unit] = {
      val orderedExisting = orderedStopListWithFutureTimesRemoved.filter(elem => elem._3.isDefined)
      if (orderedExisting.nonEmpty) {
        if (isSorted(orderedExisting.map(elem => elem._3.get), (a: Long, b: Long) => a < b)) ().successNel
        else s"Arrival times in list are not in time order. Stop list: $orderedStopListWithFutureTimesRemoved".failureNel
      } else s"No elements in filtered sequence".failureNel
    }

    def isSorted[A](as: Seq[A], ordered: (A, A) => Boolean): Boolean = {

      def helper(pos: Int): Boolean = {
        if (pos == as.length - 1) true
        else if (ordered(as(pos), as(pos + 1))) helper(pos + 1)
        else false
      }
      helper(0)
    }

    (minimumNumberOfRecordsReceived
      |@| noGapsInSequence
      |@| stopArrivalTimesAreIncremental).tupled
      .map(_ => orderedStopListWithFutureTimesRemoved
        .filter(elem => elem._3.isDefined)
        .map(elem => StopDataRecordToPersist(elem._1 + 1, elem._2.stopID, elem._3.get)))
    }

  override def postStop: Unit = logger.info(s"Vehicle $name has been killed")
}
