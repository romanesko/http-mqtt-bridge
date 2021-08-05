import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.javalin.Javalin
import io.javalin.http.ForbiddenResponse
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
data class Request(val secret: String, val topic: String, val ack_topic: String?, val ack_timeout: Long = 5, val message: String?)
data class Response(val message: String)

fun main() {

    val logger: org.slf4j.Logger = LoggerFactory.getLogger("main") // Setup the logger

    /* Require the environment variables */
    val mqttServerUri: String = System.getenv("MQTT_SERVER_URI") ?: throw RuntimeException("env MQTT_SERVER_URI is not defined (like tcp://localhost:17050)")
    val mqttUserName: String = System.getenv("MQTT_USERNAME") ?: throw RuntimeException("env MQTT_USERNAME is not defined")
    val mqttPassword: String = System.getenv("MQTT_PASSWORD") ?: throw RuntimeException("env MQTT_PASSWORD is not defined")
    val secret: String = System.getenv("SECRET") ?: throw RuntimeException("env SECRET is not defined")

    /* Take a new instance of MqttClient  */
    val client = MqttClient(mqttServerUri, "http-mqtt-bridge");

    /* Make the client to connect */
    client.connectWithResult(MqttConnectOptions().apply {
        userName = mqttUserName
        password = mqttPassword.toCharArray()
    });

    /* Start web server instance on 8080 */
    val app = Javalin.create().apply {
        exception(Exception::class.java) { e, _ -> e.printStackTrace() }
    }.start("0.0.0.0", 8080)

    /* Register handler to wrap TimeoutException to return json */
    app.exception(java.util.concurrent.TimeoutException::class.java) { e, ctx ->
        ctx.status(408) // HTTP Status code to 408
        ctx.json(mapOf("error" to "Timeout")) // {"error":"Timeout"}
    }

    /* Define storage Map<String,Future> for Futures */
    val futures: ConcurrentMap<String, CompletableFuture<Response>> = ConcurrentHashMap()

    /* Subscribe mqtt client to wildcard # (every topic) */
    client.subscribe("#") { topic, message -> // callback on message arrive
        val fut = futures[topic] // take Future for storage by key
        if (fut != null) {
            logger.info("topic '$topic' got message «$message», resolving")
            fut.complete(Response(message.toString())) // resolve Future with Response object
        }
    }

    /* Register web server endpoint */
    app.post("/") { ctx ->
        val body = ctx.bodyAsClass(Request::class.java)  // match request body to class Request

        /* Simple authentication */
        if(body.secret != secret){
            throw ForbiddenResponse("Wrong secret")
        }

        if (body.ack_topic != null) { // if body has param to await callback
            try {
                /* Return Response object when Future is resolved */
                ctx.json(CompletableFuture<Response>().apply { // creating a Future
                    logger.info("adding listener for ${body.ack_topic} with timeout ${body.ack_timeout} sec")
                    futures[body.ack_topic] = this; // write the Future into the storage
                    client.publish(body.topic, MqttMessage(body.message?.toByteArray())) // publish the message to MQTT channel
                }.get(body.ack_timeout, TimeUnit.SECONDS)) // start Future awaiting with timeout
            } finally {
                logger.info("removing listener for ${body.ack_topic}")
                futures.remove(body.ack_topic) // remove the Future from the storage
            }
        } else {
            client.publish(body.topic, MqttMessage(body.message?.toByteArray())) // publish the message to MQTT channel
            ctx.json(Response("ok"))
        }
    }


}


