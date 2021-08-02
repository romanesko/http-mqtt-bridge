import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.javalin.Javalin
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import java.lang.RuntimeException
import java.util.*
import java.util.concurrent.CompletableFuture
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

    class TimeoutException(message: String) : Exception(message)

    app.exception(TimeoutException::class.java) { e, ctx ->
        ctx.status(408)
        ctx.json(mapOf("error" to e.message))
    }

    val futures: MutableMap<String, CompletableFuture<Response>> = HashMap()

    client.subscribe("#") { topic, message ->
        run {
            futures[topic]?.let {
                it.complete(Response(message.toString()))
                futures.remove(topic)
            }
        }
    }

    fun waitFor(key: String, timeout: Long) = CompletableFuture<Response>().apply {
        futures[key] = this;

        Timer(key, false).schedule(timeout * 1000) {
            val future = futures[key]!!
            if (!future.isDone) {
                futures.remove(key)
                future.completeExceptionally(TimeoutException("Could not get response for $timeout sec"))
            }

        }
    }

    app.post("/") { ctx ->
        val body = ctx.bodyAsClass(Request::class.java)
        if (body.ack_topic != null) {
            ctx.json(waitFor(body.ack_topic, body.ack_timeout))
        } else {
            ctx.json(Response("ok"))
        }
    }


}


