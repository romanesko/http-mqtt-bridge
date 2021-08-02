import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.javalin.Javalin
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule

@JsonIgnoreProperties(ignoreUnknown = true)
data class Request(val topic: String, val ack_topic: String?, val ack_timeout: Long = 5, val message: String?)
data class Response(val message: String)

fun main() {

    val mqttServerUri: String = System.getenv("MQTT_SERVER_URI") ?: throw RuntimeException("env MQTT_SERVER_URI is not defined (like tcp://localhost:17050)")
    val mqttUserName: String = System.getenv("MQTT_USERNAME") ?: throw RuntimeException("env MQTT_USERNAME is not defined")
    val mqttPassword: String = System.getenv("MQTT_PASSWORD") ?: throw RuntimeException("env MQTT_PASSWORD is not defined")

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
        ctx.json(mapOf("error" to e.message))
    }

    val futures: ConcurrentMap<String, CompletableFuture<Response>> = ConcurrentHashMap()

    client.subscribe("#") { topic, message ->  futures[topic]?.complete(Response(message.toString())) }

    app.post("/") { ctx ->
        val body = ctx.bodyAsClass(Request::class.java)
        if (body.ack_topic != null) {
            try{
                ctx.json(CompletableFuture<Response>().apply {
                    futures[body.ack_topic] = this;
                }.get(body.ack_timeout, TimeUnit.SECONDS))
            } finally {
                futures.remove(body.ack_topic)
            }
        } else {
            ctx.json(Response("ok"))
        }
    }


}


