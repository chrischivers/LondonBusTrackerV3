package lbt.historical

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.actor.ActorSystem
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.{ChannelOwner, ConnectionOwner}
import com.rabbitmq.client.ConnectionFactory
import lbt.MessagingConfig
import net.liftweb.json.Serialization.write
import net.liftweb.json._
import scala.concurrent.duration._

class HistoricalDbInsertPublisher(messagingConfig: MessagingConfig)(implicit actorSystem: ActorSystem) {

  implicit val formats = DefaultFormats

  val connFactory = new ConnectionFactory()
  connFactory.setUri(messagingConfig.rabbitUrl)
  val conn = actorSystem.actorOf(ConnectionOwner.props(connFactory, 1 second))
  val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

  waitForConnection(actorSystem, conn, producer).await(10, TimeUnit.SECONDS)

  producer ! DeclareExchange(ExchangeParameters(messagingConfig.exchangeName, passive = true, "direct"))

  def publish (recordedDataToPersist: RecordedVehicleDataToPersist) = {
    val jsonBytes = write(recordedDataToPersist).getBytes()
    producer ! Publish(messagingConfig.exchangeName, messagingConfig.historicalDbRoutingKey, jsonBytes, properties = None, mandatory = true, immediate = false)
   }
}
