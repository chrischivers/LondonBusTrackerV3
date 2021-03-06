package lbt.database.definitions

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.dynamodbv2.model.{Condition, CreateTableRequest, CreateTableResult, DeleteTableRequest}
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper, AttributeValue, DynamoDBSerializer, Schema}
import com.typesafe.scalalogging.StrictLogging
import lbt.comon.Commons.BusRouteDefinitions
import lbt.comon.{BusRoute, BusStop}
import com.github.dwhjames.awswrap.dynamodb._
import lbt.database.DatabaseControllers
import lbt.{ConfigLoader, DatabaseConfig}
import net.liftweb.json.DefaultFormats
import net.liftweb.json.Serialization.write
import net.liftweb.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class DefinitionsDBItem(ROUTE_ID_DIRECTION: String, SEQUENCE_NO: Int, STOP_ID: String, STOP_NAME: String, LATITUDE: Double, LONGITUDE: Double)

class DefinitionsDynamoDBController(databaseConfig: DatabaseConfig)(implicit val ec: ExecutionContext) extends DatabaseControllers with StrictLogging {

  val credentials = new ProfileCredentialsProvider("lbt")
  val sdkClient = new AmazonDynamoDBAsyncClient(credentials)
  sdkClient.setRegion(Region.getRegion(Regions.US_WEST_2))
  val client = new AmazonDynamoDBScalaClient(sdkClient)
  val mapper = AmazonDynamoDBScalaMapper(client)
  implicit val formats = DefaultFormats

  object Attributes {
    val route = "ROUTE_ID_DIRECTION"
    val seqNo = "SEQUENCE_NO"
    val stopID = "STOP_ID"
    val stopName = "STOP_NAME"
    val latitude = "LATITUDE"
    val longitude = "LONGITUDE"
  }

  implicit object definitionsSerializer extends DynamoDBSerializer[DefinitionsDBItem] {

    override val tableName = databaseConfig.busDefinitionsTableName
    override val hashAttributeName = Attributes.route
    override def rangeAttributeName = Some(Attributes.seqNo)
    override def primaryKeyOf(definitionsItem: DefinitionsDBItem) =
      Map(Attributes.route -> definitionsItem.ROUTE_ID_DIRECTION,
        Attributes.seqNo -> definitionsItem.SEQUENCE_NO)
    override def toAttributeMap(definitionsItem: DefinitionsDBItem) =
      Map(
        Attributes.route -> definitionsItem.ROUTE_ID_DIRECTION,
        Attributes.seqNo -> definitionsItem.SEQUENCE_NO,
        Attributes.stopID -> definitionsItem.STOP_ID,
        Attributes.stopName -> definitionsItem.STOP_NAME,
        Attributes.latitude -> definitionsItem.LATITUDE,
        Attributes.longitude -> definitionsItem.LONGITUDE
      )
    override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) =
      DefinitionsDBItem(
        ROUTE_ID_DIRECTION = item(Attributes.route),
        SEQUENCE_NO = item(Attributes.seqNo),
        STOP_ID = item(Attributes.stopID),
        STOP_NAME = item(Attributes.stopName),
        LATITUDE = item(Attributes.latitude),
        LONGITUDE = item(Attributes.longitude)
      )
  }

  createDefinitionsTableIfNotExisting

  def insertRouteIntoDB(busRoute: BusRoute, busStopsSequence: List[BusStop]): Unit = {
    numberInsertsRequested.incrementAndGet()
    val sequenceWithIndex = busStopsSequence.zipWithIndex
    val definitionItems: Seq[DefinitionsDBItem] = sequenceWithIndex.map(stop => {
      DefinitionsDBItem(write(busRoute), stop._2, stop._1.stopID, stop._1.stopName, stop._1.latitude, stop._1.longitude)
    })
   mapper.batchDump(definitionItems).onComplete {
      case Success(_) => numberInsertsCompleted.incrementAndGet()
        logger.info(s"Inserted definitions into DB for Bus Route $busRoute")
      case Failure(e) => numberInsertsFailed.incrementAndGet()
        logger.error(s"An error has occurred inserting definition to DB for busRoute $busRoute", e)
    }
  }

  def loadBusRouteDefinitionsFromDB: BusRouteDefinitions = {
    logger.info("Loading Bus Route Definitions From DB")
    numberGetsRequested.incrementAndGet()

    val mappedResult = mapper.scan[DefinitionsDBItem]().map(result =>
      result.groupBy(item => item.ROUTE_ID_DIRECTION)
        .map(result => parse(result._1).extract[BusRoute] ->
          result._2.sortBy(stop => stop.SEQUENCE_NO)
            .map(stop => BusStop(stop.STOP_ID, stop.STOP_NAME, stop.LATITUDE, stop.LONGITUDE)).toList))

    Await.result(mappedResult, 300 seconds)
  }


  def createDefinitionsTableIfNotExisting = {
    if(!sdkClient.listTables().getTableNames.contains(databaseConfig.busDefinitionsTableName)) {
      logger.info("Creating Definitions Table...")
      val createTableRequest =
        new CreateTableRequest()
          .withTableName(databaseConfig.busDefinitionsTableName)
          .withProvisionedThroughput(
            Schema.provisionedThroughput(10L, 5L))
          .withAttributeDefinitions(
            Schema.stringAttribute(Attributes.route),
            Schema.numberAttribute(Attributes.seqNo))
          .withKeySchema(
            Schema.hashKey(Attributes.route),
            Schema.rangeKey(Attributes.seqNo))

      val createTableCommand = Future(sdkClient.createTableAsync(createTableRequest).get())
      Await.result(createTableCommand, 20 seconds)
      Thread.sleep(5000)
    } else logger.info("Definitions Table already exists. Using existing.")
  }

  def deleteTable = {
    if (sdkClient.listTables().getTableNames.contains(databaseConfig.busDefinitionsTableName)) {
      logger.info("Deleting Definitions Table...")
      val deleteTableRequest =
        new DeleteTableRequest()
          .withTableName(databaseConfig.busDefinitionsTableName)
      val deleteTableCommand = Future(sdkClient.deleteTableAsync(deleteTableRequest).get())
      Await.result(deleteTableCommand, 20 seconds)
      Thread.sleep(3000)
    } else logger.info("No table exists to delete")
  }
}