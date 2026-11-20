package io.github.iw.durableatlas.analyser

/**
 * Activities written to try how the requests they make of other services are read.
 *
 * The Kotlin files of [FILES] are the sources of a service, module `:app`, and [RESOURCES] its
 * configuration. [LIBRARY] holds the MicroProfile Config and SmallRye Config annotations and
 * classes the sources use, as Java files, and [CLIENT] the client of the repository's own that
 * calls remote functions, as a module of its own, `:client`: without them, the Kotlin files read
 * as a degraded model has them, where neither resolves.
 *
 * The tests name requests by their lines in these files, counted from the `package` line.
 */
internal object RequestSources {
    private const val HEADER = """package requests

import com.example.functions.FunctionRequest
import com.example.functions.ServiceClient
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
"""

    /** File name to text. */
    val FILES: Map<String, String> = linkedMapOf(
        // Lines 23 to 64 of each file are after the header; requests are named by line.
        "Requested" to HEADER + """
@WorkflowInterface
interface Requested {
    @WorkflowMethod
    fun run(id: String)
}

class RequestedImpl : Requested {
    private val options = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
    private val http = Workflow.newActivityStub(HttpCalls::class.java, options)
    private val clients = Workflow.newActivityStub(ClientCalls::class.java, options)
    private val notices = Workflow.newActivityStub(Notices::class.java, options)
    private val remote = Workflow.newActivityStub(Remote::class.java, options)

    override fun run(id: String) {
        http.helper(id)
        clients.named(id)
        notices.announce(id)
        remote.call(id)
        Workflow.sleep(Duration.ofSeconds(1))
    }
}

/** Implemented by two classes, each of which one worker registers. */
@ActivityInterface
interface Notices {
    fun announce(message: String)
}

class MailNotices : Notices {
    private val client = HttpClient.newHttpClient()

    override fun announce(message: String) {
        client.send(HttpRequest.newBuilder(URI.create("https://mail.example.com/send")).POST(BodyPublishers.ofString(message)).build(), BodyHandlers.ofString())
    }
}

class TextNotices(private val client: ServiceClient) : Notices {
    override fun announce(message: String) {
        client.invoke<Unit>(functionName = "text-send", payload = message)
    }
}

/** Implemented by a worker whose code is not in these sources. */
@ActivityInterface
interface Remote {
    fun call(id: String)
}
""",
        "HttpCalls" to HEADER + """
data class Order(val id: String)

@ActivityInterface
interface HttpCalls {
    fun inline(id: String): String

    fun helper(id: String): String

    fun twoHelpers(id: String)

    fun async(id: String)

    fun patch(id: String)

    fun template(order: Order)

    fun plus(id: String)

    fun resolved(id: String)

    fun formatted(id: String)

    fun constructed(id: String)

    fun head()

    fun elsewhere(request: HttpRequest)

    fun unsent(id: String)

    fun looped(ids: List<String>)

    fun twoWays(id: String, again: Boolean)

    fun computedMethod(verb: String)

    fun deep(id: String)
}

class HttpCallsImpl : HttpCalls {
    private val client = HttpClient.newHttpClient()
    private val base = "https://api.example.com"

    override fun inline(id: String): String =
        client.send(HttpRequest.newBuilder().uri(URI.create("https://api.example.com/orders")).GET().build(), BodyHandlers.ofString()).body()

    override fun helper(id: String): String = exchange("POST", "/orders/${'$'}id")

    override fun twoHelpers(id: String) {
        outer("/stock/" + id)
    }

    private fun outer(path: String) {
        exchange("DELETE", path)
    }

    private fun exchange(method: String, path: String): String {
        val request = HttpRequest.newBuilder(URI.create(base + path)).method(method, BodyPublishers.noBody()).build()
        return client.send(request, BodyHandlers.ofString()).body()
    }

    override fun async(id: String) {
        client.sendAsync(HttpRequest.newBuilder(URI.create("${'$'}base/events")).POST(BodyPublishers.ofString(id)).build(), BodyHandlers.ofString())
    }

    override fun patch(id: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${'$'}base/orders/${'$'}id"))
            .method("PATCH", BodyPublishers.noBody())
            .build()
        client.send(request, BodyHandlers.discarding())
    }

    override fun template(order: Order) {
        client.send(HttpRequest.newBuilder(URI.create("${'$'}{base}/orders/${'$'}{order.id}/lines")).build(), BodyHandlers.ofString())
    }

    override fun plus(id: String) {
        val uri = URI.create(base + "/parcels/" + id)
        client.send(HttpRequest.newBuilder(uri).PUT(BodyPublishers.noBody()).build(), BodyHandlers.ofString())
    }

    override fun resolved(id: String) {
        val root = URI.create("https://api.example.com/v2/")
        client.send(HttpRequest.newBuilder(root.resolve("refunds/" + id)).DELETE().build(), BodyHandlers.ofString())
        client.send(HttpRequest.newBuilder(root.resolve("/health")).build(), BodyHandlers.ofString())
    }

    override fun formatted(id: String) {
        val uri = URI.create(String.format("%s/invoices/%s", base, id))
        client.send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofString())
        client.send(HttpRequest.newBuilder(URI.create("%s/credits/%d".format(base, id.length))).build(), BodyHandlers.ofString())
    }

    override fun constructed(id: String) {
        client.send(HttpRequest.newBuilder().uri(URI("${'$'}base/labels/${'$'}id")).build(), BodyHandlers.ofString())
    }

    override fun head() {
        client.send(HttpRequest.newBuilder(URI.create(base)).HEAD().build(), BodyHandlers.discarding())
    }

    override fun elsewhere(request: HttpRequest) {
        client.send(request, BodyHandlers.ofString())
    }

    override fun unsent(id: String) {
        HttpRequest.newBuilder(URI.create("${'$'}base/drafts/${'$'}id")).build()
    }

    override fun looped(ids: List<String>) {
        for (id in ids) {
            exchange("POST", "/batch")
        }
    }

    override fun twoWays(id: String, again: Boolean) {
        if (again) exchange("GET", "/twice") else exchange("GET", "/twice")
        exchange("GET", "/once/${'$'}id")
    }

    override fun computedMethod(verb: String) {
        exchange(verb.uppercase(), "/any")
    }

    override fun deep(id: String) {
        first(id)
    }

    private fun first(id: String) = second(id)

    private fun second(id: String) = exchange("GET", "/deep/${'$'}id")
}

@ActivityInterface
interface HeldCalls {
    fun held(id: String)

    fun heldMethod(id: String)
}

class HeldCallsImpl : HeldCalls {
    private val client = HttpClient.newHttpClient()

    override fun held(id: String) {
        val builder = HttpRequest.newBuilder()
        builder.uri(URI.create("https://api.example.com/held/${'$'}id"))
        builder.POST(HttpRequest.BodyPublishers.noBody())
        client.send(builder.build(), BodyHandlers.ofString())
    }

    override fun heldMethod(id: String) {
        val builder = HttpRequest.newBuilder(URI.create("https://api.example.com/kept/${'$'}id")).PUT(HttpRequest.BodyPublishers.noBody())
        builder.header("Accept", "text/plain")
        client.send(builder.build(), BodyHandlers.ofString())
    }
}
""",
        "ClientCalls" to HEADER + """
const val TOP_LEVEL = "top-level-fn"

object Names {
    const val IN_OBJECT = "object-fn"
}

class Catalogue {
    companion object {
        const val OTHER = "catalogue-fn"
    }
}

data class Item(val sku: String)

@ActivityInterface
interface ClientCalls {
    fun named(id: String): List<Item>

    fun positional(id: String): Item

    fun ownConstant(id: String)

    fun otherConstant(id: String)

    fun inObject(id: String)

    fun topLevel(id: String)

    fun throughProperty(id: String)

    fun throughHelper(id: String): Item

    fun templated(region: String)

    fun operator(id: String): Item

    fun expected(id: String)

    fun requestObject(id: String)

    fun builder(id: String)

    fun functionValue(id: String)

    fun configured(id: String)

    fun discarded(id: String)
}

class ClientCallsImpl(private val client: ServiceClient) : ClientCalls {
    private val property = OWN

    @ConfigProperty(name = "functions.lookup")
    lateinit var lookupName: String

    override fun named(id: String): List<Item> = client.invoke<List<Item>>(functionName = "named-fn", payload = id)

    override fun positional(id: String): Item = client.invoke("positional-fn", id)

    override fun ownConstant(id: String) {
        client.invoke<Unit>(functionName = OWN, payload = id)
    }

    override fun otherConstant(id: String) {
        client.invoke<Unit>(functionName = Catalogue.OTHER, payload = id)
    }

    override fun inObject(id: String) {
        client.invoke<Unit>(functionName = Names.IN_OBJECT, payload = id)
    }

    override fun topLevel(id: String) {
        client.invoke<Unit>(functionName = TOP_LEVEL, payload = id)
    }

    override fun throughProperty(id: String) {
        client.invoke<Unit>(functionName = property, payload = id)
    }

    override fun throughHelper(id: String): Item = fetch(HELPED, id)

    private fun fetch(name: String, payload: Any): Item = client(functionName = name, payload = payload)

    override fun templated(region: String) {
        client.invoke<Unit>(functionName = "${'$'}region-${'$'}OWN", payload = region)
    }

    override fun operator(id: String): Item = client<Item>(functionName = OWN, payload = id)

    override fun expected(id: String) {
        val item: Item = client.invoke(functionName = HELPED, payload = id)
        println(item)
    }

    override fun requestObject(id: String) {
        client.call<Item>(FunctionRequest(functionName = OWN, payload = id))
    }

    override fun builder(id: String) {
        client.call<Item>(FunctionRequest.builder().functionName(Catalogue.OTHER).payload(id).build())
    }

    override fun functionValue(id: String) {
        val handler: (String) -> Unit = { println(it) }
        handler.invoke(id)
    }

    override fun configured(id: String) {
        client.invoke<Unit>(functionName = lookupName, payload = id)
    }

    override fun discarded(id: String) {
        println(client.invoke<Item>("discarded-fn", id))
    }

    companion object {
        const val OWN = "own-fn"
        const val HELPED = "helped-fn"
    }
}
""",
        "ConfigCalls" to HEADER + """
@ConfigMapping(prefix = "services")
interface Services {
    fun inventoryUrl(): String

    val billingUrl: String

    fun shipping(): Shipping

    interface Shipping {
        @WithName("base")
        fun url(): String

        @WithDefault("/v1")
        fun version(): String
    }
}

@ActivityInterface
interface ConfigCalls {
    fun field(id: String)

    fun annotatedField(id: String)

    fun constructorParameter(id: String)

    fun mapping(id: String)

    fun mappingProperty(id: String)

    fun group(id: String)

    fun provided(id: String)

    fun optional(id: String)

    fun missing(id: String)

    fun defaulted(id: String)

    fun profiled(id: String)
}

class ConfigCallsImpl(
    @ConfigProperty(name = "orders.url") private val ordersUrl: String,
    private val services: Services,
) : ConfigCalls {
    private val client = HttpClient.newHttpClient()

    @ConfigProperty(name = "stock.url")
    lateinit var stockUrl: String

    @field:ConfigProperty(name = "audit.url")
    var auditUrl: String = ""

    @ConfigProperty(name = "absent.url", defaultValue = "http://fallback.example.com")
    lateinit var absentUrl: String

    @ConfigProperty(name = "nowhere.url")
    lateinit var nowhereUrl: String

    private fun get(uri: URI) {
        client.send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofString())
    }

    override fun field(id: String) = get(URI.create("${'$'}stockUrl/items/${'$'}id"))

    override fun annotatedField(id: String) = get(URI.create(auditUrl + "/entries"))

    override fun constructorParameter(id: String) = get(URI.create("${'$'}ordersUrl/orders/${'$'}id"))

    override fun mapping(id: String) = get(URI.create(services.inventoryUrl() + "/reserve"))

    override fun mappingProperty(id: String) = get(URI.create("${'$'}{services.billingUrl}/bills"))

    override fun group(id: String) {
        val shipping = services.shipping()
        get(URI.create(shipping.url() + shipping.version() + "/parcels"))
    }

    override fun provided(id: String) {
        val url = ConfigProvider.getConfig().getValue("provided.url", String::class.java)
        get(URI.create(url))
    }

    override fun optional(id: String) {
        val url = ConfigProvider.getConfig().getOptionalValue("optional.url", String::class.java).orElse("http://none.example.com")
        get(URI.create("${'$'}url/ping"))
    }

    override fun missing(id: String) = get(URI.create(nowhereUrl))

    override fun defaulted(id: String) = get(URI.create(absentUrl))

    override fun profiled(id: String) = get(URI.create(ConfigProvider.getConfig().getValue("only.prod.url", String::class.java)))
}
""",
    )

    /** The configuration of `:app`. */
    val RESOURCES: Map<String, String> = linkedMapOf(
        "application.yaml" to """
            orders:
              url: http://orders.example.com
            stock:
              url: ${'$'}{STOCK_URL:http://stock.example.com}
            services:
              inventory-url: http://inventory.example.com
              get-billing-url: http://billing.example.com
              shipping:
                base: http://shipping.example.com
            functions:
              lookup: lookup-fn
            "%prod":
              audit:
                url: https://audit.example.com
              orders:
                url: https://orders.prod.example.com
              only:
                prod:
                  url: https://prod.example.com
        """,
        "application.properties" to """
            orders.url=http://orders-from-properties.example.com
            provided.url=http://provided.example.com
            optional.url=http://optional.example.com
        """,
    )

    /** The client of the repository's own, in a module of its own. */
    val CLIENT: Map<String, String> = linkedMapOf(
        "com/example/functions/ServiceClient.kt" to """
            package com.example.functions

            class FunctionRequest(val functionName: String, val payload: Any) {
                class Builder {
                    private var functionName = ""
                    private var payload: Any = ""

                    fun functionName(name: String): Builder = apply { functionName = name }

                    fun payload(value: Any): Builder = apply { payload = value }

                    fun build(): FunctionRequest = FunctionRequest(functionName, payload)
                }

                companion object {
                    fun builder(): Builder = Builder()
                }
            }

            class ServiceClient {
                inline operator fun <reified T> invoke(functionName: String, payload: Any): T = decode(functionName, payload, T::class.java)

                fun <T> call(request: FunctionRequest): T? = null

                fun <T> decode(name: String, payload: Any, type: Class<T>): T = throw UnsupportedOperationException(name + payload + type)
            }
        """,
    )

    /** What the build of the service adds to the classpath: MicroProfile Config and SmallRye Config, as far as the sources use them. */
    val LIBRARY: Map<String, String> = linkedMapOf(
        "org/eclipse/microprofile/config/inject/ConfigProperty.java" to """
            package org.eclipse.microprofile.config.inject;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
            public @interface ConfigProperty {
                String UNCONFIGURED_VALUE = "org.eclipse.microprofile.config.configproperty.unconfigureddvalue";

                String name() default "";

                String defaultValue() default UNCONFIGURED_VALUE;
            }
        """,
        "org/eclipse/microprofile/config/Config.java" to """
            package org.eclipse.microprofile.config;

            import java.util.Optional;

            public interface Config {
                <T> T getValue(String propertyName, Class<T> propertyType);

                <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType);
            }
        """,
        "org/eclipse/microprofile/config/ConfigProvider.java" to """
            package org.eclipse.microprofile.config;

            public final class ConfigProvider {
                public static Config getConfig() {
                    return null;
                }
            }
        """,
        "io/smallrye/config/ConfigMapping.java" to """
            package io.smallrye.config;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface ConfigMapping {
                String prefix() default "";
            }
        """,
        "io/smallrye/config/WithName.java" to """
            package io.smallrye.config;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithName {
                String value();
            }
        """,
        "io/smallrye/config/WithDefault.java" to """
            package io.smallrye.config;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface WithDefault {
                String value();
            }
        """,
    )
}
