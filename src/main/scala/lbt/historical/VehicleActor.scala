package lbt.historical

import akka.actor.Actor
import com.typesafe.scalalogging.StrictLogging
import lbt.HistoricalRecordsConfig
import lbt.comon.{BusRoute, BusStop}
import lbt.database.definitions.BusDefinitionsCollection
import lbt.database.historical.HistoricalRecordsCollection

import scalaz.Scalaz._
import scalaz._

case class RecordedVehicleDataToPersist(vehicleID: String, busRoute: BusRoute, stopArrivalRecords: List[(Int, BusStop, Long)])

class VehicleActor(vehicleReg: String, historicalRecordsConfig: HistoricalRecordsConfig, busDefinitionsCollection: BusDefinitionsCollection, historicalRecordsCollection: HistoricalRecordsCollection) extends Actor with StrictLogging {
  val name: String = self.path.name
  type StringValidation[T] = ValidationNel[String, T]

  def receive: Receive = active(None, Map.empty, List.empty)

  def active(route: Option[BusRoute],
             stopArrivalRecords: Map[BusStop, Long],
             busStopDefinitionList: List[BusStop]): Receive = {

    case vsl: ValidatedSourceLine =>
      assert(vsl.vehicleID + "-" + vsl.busRoute.id + "-" + vsl.busRoute.direction == name)

      if (route.isEmpty) {
        val stopDefinitionList = busDefinitionsCollection.getBusRouteDefinitions().apply(vsl.busRoute)
        context.become(active(Some(vsl.busRoute), stopArrivalRecords + (vsl.busStop -> vsl.arrival_TimeStamp), stopDefinitionList))
      } else {
        val stopArrivalRecordsWithLastStop = stopArrivalRecords + (vsl.busStop -> vsl.arrival_TimeStamp)
        context.become(active(Some(vsl.busRoute), stopArrivalRecordsWithLastStop, busStopDefinitionList))
      }
    case GetArrivalRecords(_) => sender ! stopArrivalRecords

    case PersistToDB =>
      logger.info("Persist to DB command received. Attempting to persist...")
      validateBeforePersist(route.get, stopArrivalRecords, busStopDefinitionList) match {
        case Success(completeList) => historicalRecordsCollection.insertHistoricalRecordIntoDB(RecordedVehicleDataToPersist(vehicleReg, route.get, completeList))
        case Failure(e) => logger.info(s"Failed validation before persisting. Stop Arrival Records. Error: $e. \n Stop Arrival Records: $stopArrivalRecords.")
      }
  }

  def validateBeforePersist(route: BusRoute, stopArrivalRecords: Map[BusStop, Long], busStopDefinitionList: List[BusStop]): StringValidation[List[(Int, BusStop, Long)]] = {

    val orderedStopsList: List[(Int, BusStop, Option[Long])] = busStopDefinitionList.zipWithIndex.map{case(stop, index) => (index, stop, stopArrivalRecords.get(stop))}

    def minimumNumberOfRecordsReceived: StringValidation[Unit] = {
      val numberOfRecordsWithData = orderedStopsList.count(rec => rec._3.isDefined)
      if(numberOfRecordsWithData >= historicalRecordsConfig.minimumNumberOfStopsToPersist) ().successNel
      else s"Not enough stops to persist. Number of stops: $numberOfRecordsWithData. Minimum ${historicalRecordsConfig.minimumNumberOfStopsToPersist}".failureNel
    }

    def noGapsInSequence: StringValidation[Unit] = {
      val orderedExisting = orderedStopsList.filter(elem => elem._3.isDefined)
      if (orderedExisting.size == orderedExisting.last._1 - orderedExisting.head._1 + 1) ().successNel
      else  s"Gaps encountered in the sequence received (excluding those at beginning and/or end. StopList: $orderedStopsList".failureNel
    }

    def stopArrivalTimesAreIncremental: StringValidation[Unit] = {
      if (isSorted(orderedStopsList.filter(elem => elem._3.isDefined).map(elem => elem._3.get), (a: Long, b: Long) => a < b)) ().successNel
      else s"Arrival times in list are not in time order. Stop list: $orderedStopsList".failureNel
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
      .map(_ => orderedStopsList
        .filter(elem => elem._3.isDefined)
        .map(elem => (elem._1, elem._2, elem._3.get)))
    }

  override def postStop: Unit = logger.info(s"Vehicle $name has been stopped")
}
