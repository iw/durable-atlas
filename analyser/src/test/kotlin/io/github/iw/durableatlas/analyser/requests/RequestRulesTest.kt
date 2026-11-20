package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.TypeRef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The rules the reading of requests keeps to, one by one: `--client`, configuration files, names and templates. */
class RequestRulesTest {
    // ---- --client

    @Test
    fun `a client is named by its method, optionally its type, and the parameter or place of its operation`() {
        assertEquals(ClientSpec("ServiceClient", "invoke", parameter = "functionName"), ClientSpec.parse("ServiceClient.invoke:functionName"))
        assertEquals(ClientSpec(null, "invoke", parameter = "functionName"), ClientSpec.parse("invoke:functionName"))
        assertEquals(ClientSpec("com.example.Client", "call", position = 2), ClientSpec.parse("com.example.Client.call:2"))
        assertEquals(ClientSpec("Client", "call"), ClientSpec.parse("Client.call"))
        assertEquals("Client", ClientSpec.parse("com.example.Client.call").simpleType)
        for (text in listOf("ServiceClient.invoke:functionName", "invoke:2", "a.b.C.call", "send")) {
            assertEquals(text, ClientSpec.parse(text).toString())
        }
    }

    @Test
    fun `a client that is not written in the form is refused with the form in the message`() {
        for (text in listOf("", "Client.", ".invoke", "Client.invoke()", "Client invoke", "Client.invoke:", "invoke:0", "invoke:a-b", "1Client.invoke", "invoke:x:y")) {
            val failure = assertFailsWith<IllegalArgumentException>(text) { ClientSpec.parse(text) }
            assertTrue("does not name a client" in failure.message.orEmpty(), failure.message)
        }
        assertTrue(ClientSpec.FORM in assertFailsWith<IllegalArgumentException> { ClientSpec.parse("Client.invoke()") }.message.orEmpty())
    }

    // ---- configuration files

    @Test
    fun `nested YAML maps are read as dotted keys, and lists by their places`() {
        val values = Configuration.yaml(
            """
            services:
              inventory:
                url: http://inventory.example.com
                port: 8080
              hosts:
                - a.example.com
                - b.example.com
            "%prod":
              services:
                inventory:
                  url: https://inventory.example.com
            empty:
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                "services.inventory.url" to "http://inventory.example.com",
                // Every value is text, as written.
                "services.inventory.port" to "8080",
                "services.hosts[0]" to "a.example.com",
                "services.hosts[1]" to "b.example.com",
                "%prod.services.inventory.url" to "https://inventory.example.com",
            ),
            values,
        )
    }

    @Test
    fun `a key's value without a profile counts before that of prod, YAML before properties, and an expression is kept`() {
        val configuration = Configuration.of(
            mapOf(
                "application.properties" to "a=from-properties\nb=from-properties\n%prod.c=prod-from-properties\nd=\${D_URL:http://d.example.com}\n",
                "application.yaml" to "a: from-yaml\n\"%prod\":\n  b: prod-from-yaml\n  c: prod-from-yaml\n",
            ),
        )
        assertEquals("from-yaml", configuration.value("a"))
        // A value without a profile wins over one of `prod`, whichever file gives it.
        assertEquals("from-properties", configuration.value("b"))
        assertEquals("prod-from-yaml", configuration.value("c"))
        assertEquals("\${D_URL:http://d.example.com}", configuration.value("d"))
        assertNull(configuration.value("e"))
        // Another profile does not count.
        assertNull(Configuration.of(mapOf("application.properties" to "%dev.e=x")).value("e"))
    }

    @Test
    fun `application yaml counts before application yml, and a file that cannot be read gives nothing and is reported`(@TempDir directory: Path) {
        val yml = Files.writeString(directory.resolve("application.yml"), "a: yml\nb: yml\n")
        val yaml = Files.writeString(directory.resolve("application.yaml"), "a: yaml\n")
        val broken = Files.createDirectories(directory.resolve("broken")).resolve("application.yaml")
        Files.writeString(broken, "a: [unclosed\n")
        val problems = ArrayList<String>()
        val configuration = Configuration.read(listOf(yml, yaml, broken), problems::add)
        assertEquals("yaml", configuration.value("a"))
        assertEquals("yml", configuration.value("b"))
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().startsWith("$broken could not be read"), problems.single())
        assertTrue(Configuration.NONE.isEmpty)
    }

    // ---- names

    @Test
    fun `an accessor of a config mapping reads its name in kebab case, and a property its getter's`() {
        // As SmallRye Config 4.2 names them.
        val names = mapOf(
            "baseUrl" to "base-url",
            "apiURL" to "api-url",
            "hTTPClient" to "h-ttp-client",
            "url2Path" to "url2-path",
            "getThing" to "get-thing",
            "url" to "url",
        )
        for ((accessor, key) in names) assertEquals(key, RequestValues.kebab(accessor), accessor)
        assertEquals("getInventoryUrl", RequestValues.getter("inventoryUrl"))
        assertEquals("isEnabled", RequestValues.getter("isEnabled"))
        assertEquals("getIssuer", RequestValues.getter("issuer"))
        assertEquals("get-inventory-url", RequestValues.kebab(RequestValues.getter("inventoryUrl")))
    }

    @Test
    fun `a type is named without packages, and one that did not resolve as written`() {
        val list = TypeRef(name = "kotlin.collections.List", text = "kotlin.collections.List<com.example.Item>", arguments = listOf(TypeRef("com.example.Item", "com.example.Item")))
        assertEquals("List<Item>", RequestValues.shortType(list))
        val missing = TypeRef(name = null, text = "ERROR CLASS: Symbol not found for com.example.Missing")
        assertEquals("Missing", RequestValues.shortType(missing))
        assertEquals("Missing", RequestValues.writtenType(missing))
        assertNull(RequestValues.shortType(TypeRef(name = null, text = "ERROR CLASS: Cannot infer argument for type parameter T")))
        // A type parameter stands for what it is bound to.
        assertEquals("Item", RequestValues.shortType(TypeRef(name = null, text = "T"), mapOf("T" to TypeRef("com.example.Item", "com.example.Item"))))
    }

    // ---- templates

    @Test
    fun `a string template is split into its fixed texts and the source of its expressions`() {
        assertEquals(
            listOf(Templates.Piece.Fixed("http://"), Templates.Piece.Expression("host"), Templates.Piece.Fixed("/orders/"), Templates.Piece.Expression("order.id")),
            Templates.pieces("\"http://\$host/orders/\${order.id}\""),
        )
        assertEquals(listOf(Templates.Piece.Fixed("a\"b\$c")), Templates.pieces("\"a\\\"b\\\$c\""))
        assertEquals(listOf(Templates.Piece.Expression("map[\"}\"]")), Templates.pieces("\"\${map[\"}\"]}\""))
        assertEquals(listOf(Templates.Piece.Fixed("raw \\n "), Templates.Piece.Expression("x")), Templates.pieces("\"\"\"raw \\n \$x\"\"\""))
        assertNull(Templates.pieces("notATemplate"))
    }

    @Test
    fun `parts read as one text show keys and names in braces`() {
        val parts = listOf(Part.Key("services.url"), Part.Text("/orders/"), Part.Text(""), Part.Name("id"), Part.Key("services.url")).joined()
        assertEquals("{services.url}/orders/{id}{services.url}", parts.text())
        assertEquals(listOf("services.url"), parts.keys())
        assertNull(parts.constant())
        assertEquals("ab", listOf(Part.Text("a"), Part.Text("b")).joined().constant())
    }
}
