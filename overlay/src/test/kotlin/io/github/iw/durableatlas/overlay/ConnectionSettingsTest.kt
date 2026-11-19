package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.overlay.fetch.ConnectionFlags
import io.github.iw.durableatlas.overlay.fetch.ConnectionSettings
import io.github.iw.durableatlas.overlay.fetch.ConnectionSettingsException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where connection settings come from. Every case names its own configuration file, so that the
 * one of whoever runs the tests is never read.
 */
class ConnectionSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("temporal.toml")

    private fun environment(vararg variables: Pair<String, String>): Map<String, String> =
        mapOf("TEMPORAL_CONFIG_FILE" to file.toString()) + variables

    private fun profiles(): Path =
        Files.writeString(
            file,
            """
            [profile.default]
            address = "file.example:7233"
            namespace = "file-namespace"
            api_key = "file-key"

            [profile.default.tls]
            client_cert_path = "/file/cert.pem"
            client_key_path = "/file/key.pem"
            server_ca_cert_path = "/file/ca.pem"
            server_name = "file.server"

            [profile.staging]
            address = "staging.example:7233"
            namespace = "staging"

            [profile.staging.tls]
            disabled = true
            """.trimIndent(),
        )

    @Test
    fun `without anything said the server is the local one and the namespace the default one`() {
        val settings = ConnectionSettings.resolve(ConnectionFlags(), environment())

        assertEquals("127.0.0.1:7233", settings.address)
        assertEquals("default", settings.namespace)
        assertFalse(settings.hasApiKey)
        assertFalse(settings.tls)
        assertNull(settings.tlsCertPath)
        assertNull(settings.tlsServerName)
    }

    @Test
    fun `settings come from the default profile of the configuration file`() {
        profiles()

        val settings = ConnectionSettings.resolve(ConnectionFlags(), environment())

        assertEquals("file.example:7233", settings.address)
        assertEquals("file-namespace", settings.namespace)
        assertTrue(settings.hasApiKey)
        assertTrue(settings.tls)
        assertEquals("/file/cert.pem", settings.tlsCertPath)
        assertEquals("/file/key.pem", settings.tlsKeyPath)
        assertEquals("/file/ca.pem", settings.tlsCaPath)
        assertEquals("file.server", settings.tlsServerName)
    }

    @Test
    fun `the environment goes before the profile, each setting on its own`() {
        profiles()

        val settings = ConnectionSettings.resolve(
            ConnectionFlags(),
            environment(
                "TEMPORAL_ADDRESS" to "env.example:7233",
                "TEMPORAL_TLS_CLIENT_CERT_PATH" to "/env/cert.pem",
                "TEMPORAL_TLS_SERVER_NAME" to "env.server",
            ),
        )

        assertEquals("env.example:7233", settings.address)
        assertEquals("file-namespace", settings.namespace)
        assertEquals("/env/cert.pem", settings.tlsCertPath)
        assertEquals("/file/key.pem", settings.tlsKeyPath)
        assertEquals("env.server", settings.tlsServerName)

        val all = ConnectionSettings.resolve(
            ConnectionFlags(),
            environment(
                "TEMPORAL_ADDRESS" to "env.example:7233",
                "TEMPORAL_NAMESPACE" to "env-namespace",
                "TEMPORAL_API_KEY" to "env-key",
                "TEMPORAL_TLS" to "true",
                "TEMPORAL_TLS_CLIENT_CERT_PATH" to "/env/cert.pem",
                "TEMPORAL_TLS_CLIENT_KEY_PATH" to "/env/key.pem",
                "TEMPORAL_TLS_SERVER_CA_CERT_PATH" to "/env/ca.pem",
                "TEMPORAL_TLS_SERVER_NAME" to "env.server",
            ),
        )
        assertEquals(
            listOf("env.example:7233", "env-namespace", "/env/cert.pem", "/env/key.pem", "/env/ca.pem", "env.server"),
            listOf(all.address, all.namespace, all.tlsCertPath, all.tlsKeyPath, all.tlsCaPath, all.tlsServerName),
        )
        assertTrue(all.hasApiKey)
        assertTrue(all.tls)
    }

    @Test
    fun `what the caller says goes before the environment, each setting on its own`() {
        profiles()
        val environment = environment(
            "TEMPORAL_ADDRESS" to "env.example:7233",
            "TEMPORAL_NAMESPACE" to "env-namespace",
            "TEMPORAL_TLS_CLIENT_KEY_PATH" to "/env/key.pem",
            "TEMPORAL_TLS_SERVER_NAME" to "env.server",
        )

        val some = ConnectionSettings.resolve(ConnectionFlags(namespace = "flag-namespace", tlsCaPath = "/flag/ca.pem"), environment)
        assertEquals("env.example:7233", some.address)
        assertEquals("flag-namespace", some.namespace)
        assertEquals("/file/cert.pem", some.tlsCertPath)
        assertEquals("/env/key.pem", some.tlsKeyPath)
        assertEquals("/flag/ca.pem", some.tlsCaPath)
        assertEquals("env.server", some.tlsServerName)

        val all = ConnectionSettings.resolve(
            ConnectionFlags(
                address = "flag.example:7233",
                namespace = "flag-namespace",
                apiKey = "flag-key",
                tls = true,
                tlsCertPath = "/flag/cert.pem",
                tlsKeyPath = "/flag/key.pem",
                tlsCaPath = "/flag/ca.pem",
                tlsServerName = "flag.server",
            ),
            environment,
        )
        assertEquals(
            listOf("flag.example:7233", "flag-namespace", "/flag/cert.pem", "/flag/key.pem", "/flag/ca.pem", "flag.server"),
            listOf(all.address, all.namespace, all.tlsCertPath, all.tlsKeyPath, all.tlsCaPath, all.tlsServerName),
        )
    }

    @Test
    fun `the profile is the one the caller names, else the one the environment names, else the default one`() {
        profiles()

        assertEquals("file-namespace", ConnectionSettings.resolve(ConnectionFlags(), environment()).namespace)
        assertEquals("staging", ConnectionSettings.resolve(ConnectionFlags(), environment("TEMPORAL_PROFILE" to "staging")).namespace)
        assertEquals(
            "file-namespace",
            ConnectionSettings.resolve(ConnectionFlags(profile = "default"), environment("TEMPORAL_PROFILE" to "staging")).namespace,
        )
        // A profile is what is left when nothing else speaks: the environment still goes before the profile the caller names.
        val staging = ConnectionSettings.resolve(ConnectionFlags(profile = "staging"), environment("TEMPORAL_NAMESPACE" to "env-namespace"))
        assertEquals("staging.example:7233", staging.address)
        assertEquals("env-namespace", staging.namespace)
        assertFalse(staging.tls)

        val unknown = assertFailsWith<ConnectionSettingsException> { ConnectionSettings.resolve(ConnectionFlags(profile = "nowhere"), environment()) }
        assertContains(unknown.message!!, "nowhere")
        val unknownByEnvironment = assertFailsWith<ConnectionSettingsException> {
            ConnectionSettings.resolve(ConnectionFlags(), environment("TEMPORAL_PROFILE" to "nowhere"))
        }
        assertContains(unknownByEnvironment.message!!, "nowhere")
    }

    @Test
    fun `TLS is on with an API key or a certificate, unless it is turned off`() {
        fun tls(flags: ConnectionFlags = ConnectionFlags(), vararg variables: Pair<String, String>) =
            ConnectionSettings.resolve(flags, environment(*variables)).tls

        assertFalse(tls())
        assertTrue(tls(ConnectionFlags(apiKey = "key")))
        assertTrue(tls(variables = arrayOf("TEMPORAL_API_KEY" to "key")))
        assertTrue(tls(ConnectionFlags(tls = true)))
        assertTrue(tls(variables = arrayOf("TEMPORAL_TLS" to "true")))
        assertTrue(tls(ConnectionFlags(tlsCertPath = "/cert.pem", tlsKeyPath = "/key.pem")))
        assertTrue(tls(ConnectionFlags(tlsServerName = "server")))
        assertFalse(tls(ConnectionFlags(apiKey = "key", tls = false)))
        assertFalse(tls(ConnectionFlags(tls = false), "TEMPORAL_TLS" to "true"))
        assertTrue(tls(ConnectionFlags(tls = true), "TEMPORAL_TLS" to "false"))
        assertFalse(tls(ConnectionFlags(tlsCertPath = "/cert.pem"), "TEMPORAL_TLS" to "false"))
    }

    @Test
    fun `a configuration file that cannot be read is a failure, and one that is not there is not`() {
        assertEquals("default", ConnectionSettings.resolve(ConnectionFlags(), environment()).namespace)

        Files.writeString(file, "[profile.default\naddress = ")
        val broken = assertFailsWith<ConnectionSettingsException> { ConnectionSettings.resolve(ConnectionFlags(), environment()) }
        assertContains(broken.message!!, "the Temporal configuration file cannot be read: ")
    }

    @Test
    fun `the settings are handed to the SDK, and certificates that cannot be read are a failure`() {
        val plain = ConnectionSettings.resolve(ConnectionFlags(address = "plain.example:7233"), environment()).stubsOptions()
        assertEquals("plain.example:7233", plain.target)
        assertNull(plain.sslContext)
        assertFalse(plain.enableHttps)

        val secured = ConnectionSettings.resolve(ConnectionFlags(tls = true), environment()).stubsOptions()
        assertEquals("127.0.0.1:7233", secured.target)
        assertNotNull(secured.sslContext)

        // An API key alone asks for TLS with the certificates the JVM trusts, unless TLS is turned off.
        val keyed = ConnectionSettings.resolve(ConnectionFlags(apiKey = "key"), environment()).stubsOptions()
        assertTrue(keyed.enableHttps)
        val keyedInTheClear = ConnectionSettings.resolve(ConnectionFlags(apiKey = "key", tls = false), environment()).stubsOptions()
        assertFalse(keyedInTheClear.enableHttps)
        assertNull(keyedInTheClear.sslContext)
        val keyedByEnvironment = ConnectionSettings.resolve(ConnectionFlags(), environment("TEMPORAL_API_KEY" to "key", "TEMPORAL_TLS" to "false"))
        assertFalse(keyedByEnvironment.stubsOptions().enableHttps)

        val missing = ConnectionSettings.resolve(
            ConnectionFlags(tlsCertPath = dir.resolve("cert.pem").toString(), tlsKeyPath = dir.resolve("key.pem").toString()),
            environment(),
        )
        val failure = assertFailsWith<ConnectionSettingsException> { missing.stubsOptions() }
        assertContains(failure.message!!, "the TLS settings cannot be used: ")
        assertContains(failure.message!!, "cert.pem")
    }
}
