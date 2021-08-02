import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.javalin.Javalin
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@JsonIgnoreProperties(ignoreUnknown = true)
data class Request(val topic: String, val ack_topic: String?, val ack_timeout: Long = 5, val message: String?)
data class Response(val message: String)

fun main() {

    val logger: org.slf4j.Logger = LoggerFactory.getLogger("main")

    val mqttServerUri: String = System.getenv("MQTT_SERVER_URI")
        ?: throw RuntimeException("env MQTT_SERVER_URI is not defined (like tcp://localhost:17050)")
    val mqttUserName: String =
        System.getenv("MQTT_USERNAME") ?: throw RuntimeException("env MQTT_USERNAME is not defined")
    val mqttPassword: String =
        System.getenv("MQTT_PASSWORD") ?: throw RuntimeException("env MQTT_PASSWORD is not defined")

    val client = MqttClient(mqttServerUri, "http-mqtt-bridge");

    client.connectWithResult(MqttConnectOptions().apply {
        userName = mqttUserName
        password = mqttPassword.toCharArray()
    });

    val ipAddress = "0.0.0.0"
    val app = Javalin.create().apply {
        exception(Exception::class.java) { e, _ -> e.printStackTrace() }
    }.start(ipAddress, 8080)

    app.exception(java.util.concurrent.TimeoutException::class.java) { e, ctx ->
        ctx.status(408)
        ctx.json(mapOf("error" to "Timeout"))
    }

    val futures: ConcurrentMap<String, CompletableFuture<Response>> = ConcurrentHashMap()

    client.subscribe("#") { topic, message ->
        val fut = futures[topic]
        if (fut != null) {
            logger.info("topic '$topic' got message «$message», resolving")
            fut.complete(Response(message.toString()))
        }
    }

    app.post("/") { ctx ->
        val body = ctx.bodyAsClass(Request::class.java)
        if (body.ack_topic != null) {
            try {
                ctx.json(CompletableFuture<Response>().apply {
                    logger.info("adding listener for ${body.ack_topic} with timeout ${body.ack_timeout} sec")
                    futures[body.ack_topic] = this;
                    client.publish(body.topic, MqttMessage(body.message?.toByteArray()))
                }.get(body.ack_timeout, TimeUnit.SECONDS))
            } finally {
                logger.info("removing listener for ${body.ack_topic}")
                futures.remove(body.ack_topic)
            }
        } else {
            ctx.json(Response("ok"))
        }
    }


}


