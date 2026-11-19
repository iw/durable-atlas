package io.github.iw.durableatlas.overlay.fetch

import io.grpc.ManagedChannel
import io.temporal.envconfig.ClientConfigProfile
import io.temporal.envconfig.ClientConfigTLS
import io.temporal.envconfig.LoadClientConfigProfileOptions
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import java.io.IOException

/** Thrown when the settings for the connection to a Temporal server cannot be put together. */
class ConnectionSettingsException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * What a caller says about the connection itself, such as the options of a command line. What is
 * `null` was not said, and is taken from the environment.
 *
 * @param tls `true` to connect over TLS, `false` not to
 * @param tlsCertPath file of the client certificate, in PEM form
 * @param tlsKeyPath file of the client certificate's key, in PEM form
 * @param tlsCaPath file of the certificates the server's certificate is checked against
 * @param tlsServerName the name the server's certificate is checked for, if not that of the address
 * @param profile the profile of the configuration file to start from
 */
data class ConnectionFlags(
    val address: String? = null,
    val namespace: String? = null,
    val apiKey: String? = null,
    val tls: Boolean? = null,
    val tlsCertPath: String? = null,
    val tlsKeyPath: String? = null,
    val tlsCaPath: String? = null,
    val tlsServerName: String? = null,
    val profile: String? = null,
)

/**
 * How to reach a Temporal server: where it is, the namespace, and the credentials.
 *
 * Settings are taken, each on its own, from the first of these that has it:
 *
 * 1. [ConnectionFlags]
 * 2. the `TEMPORAL_*` environment variables (`TEMPORAL_ADDRESS`, `TEMPORAL_NAMESPACE`,
 *    `TEMPORAL_API_KEY`, `TEMPORAL_TLS`, `TEMPORAL_TLS_CLIENT_CERT_PATH`,
 *    `TEMPORAL_TLS_CLIENT_KEY_PATH`, `TEMPORAL_TLS_SERVER_CA_CERT_PATH`,
 *    `TEMPORAL_TLS_SERVER_NAME` and the others Temporal's own tools read)
 * 3. a profile of Temporal's configuration file: the profile named by the flags, else by
 *    `TEMPORAL_PROFILE`, else `default`; the file named by `TEMPORAL_CONFIG_FILE`, else
 *    `temporalio/temporal.toml` in the user's configuration directory
 * 4. the defaults: [DEFAULT_ADDRESS], [DEFAULT_NAMESPACE], no credentials
 *
 * The environment and the file are read by the Temporal SDK's own support for them, so they mean
 * here what they mean to the SDK. An API key turns TLS on unless something turns it off.
 */
class ConnectionSettings private constructor(private val profile: ClientConfigProfile) {
    val address: String get() = profile.address?.ifEmpty { null } ?: DEFAULT_ADDRESS
    val namespace: String get() = profile.namespace?.ifEmpty { null } ?: DEFAULT_NAMESPACE

    /** Whether requests carry an API key. */
    val hasApiKey: Boolean get() = !profile.apiKey.isNullOrEmpty()

    /** Whether the connection is made over TLS. */
    val tls: Boolean
        get() {
            val tls = profile.tls
            return if (tls != null) tls.isDisabled != true else hasApiKey
        }

    /** The file of the client certificate, if one is used. */
    val tlsCertPath: String? get() = profile.tls?.clientCertPath?.ifEmpty { null }
    val tlsKeyPath: String? get() = profile.tls?.clientKeyPath?.ifEmpty { null }
    val tlsCaPath: String? get() = profile.tls?.serverCACertPath?.ifEmpty { null }
    val tlsServerName: String? get() = profile.tls?.serverName?.ifEmpty { null }

    /**
     * The settings as the SDK takes them.
     *
     * @param channel a connection that is already there, to use instead of making one to
     *   [address]; the API key still goes with every request
     * @throws ConnectionSettingsException if the certificates cannot be read
     */
    internal fun stubsOptions(channel: ManagedChannel? = null): WorkflowServiceStubsOptions =
        try {
            if (channel == null) {
                val options = profile.toBuilder().setAddress(address).build().toWorkflowServiceStubsOptions()
                // The SDK takes an API key as a wish for TLS unless it is told otherwise in so many words.
                if (tls) options else WorkflowServiceStubsOptions.newBuilder(options).setEnableHttps(false).build()
            } else {
                val carried = profile.toBuilder().setAddress(null).setTls(ClientConfigTLS.newBuilder().setDisabled(true).build()).build()
                WorkflowServiceStubsOptions.newBuilder(carried.toWorkflowServiceStubsOptions()).setChannel(channel).build()
            }
        } catch (e: RuntimeException) {
            throw ConnectionSettingsException("the TLS settings cannot be used: ${rootMessage(e)}", e)
        }

    companion object {
        const val DEFAULT_ADDRESS: String = "127.0.0.1:7233"
        const val DEFAULT_NAMESPACE: String = "default"

        /**
         * @param environment the environment variables to read; those of the process by default
         * @throws ConnectionSettingsException if the configuration file cannot be read, or has no
         *   profile of the name asked for
         */
        fun resolve(flags: ConnectionFlags = ConnectionFlags(), environment: Map<String, String> = System.getenv()): ConnectionSettings {
            val loaded = try {
                ClientConfigProfile.load(
                    LoadClientConfigProfileOptions.newBuilder()
                        .setEnvOverrides(environment)
                        .setConfigFileProfile(flags.profile)
                        .build(),
                )
            } catch (e: IOException) {
                throw ConnectionSettingsException("the Temporal configuration file cannot be read: ${rootMessage(e)}", e)
            } catch (e: IllegalArgumentException) {
                throw ConnectionSettingsException("the Temporal configuration cannot be used: ${e.message}", e)
            }

            val settings = loaded.toBuilder()
            flags.address?.let(settings::setAddress)
            flags.namespace?.let(settings::setNamespace)
            flags.apiKey?.let(settings::setApiKey)
            val said = listOf(flags.tls, flags.tlsCertPath, flags.tlsKeyPath, flags.tlsCaPath, flags.tlsServerName).any { it != null }
            if (said) {
                val tls = loaded.tls?.toBuilder() ?: ClientConfigTLS.newBuilder()
                flags.tls?.let { tls.setDisabled(!it) }
                // A file named here replaces the same thing given as text further down.
                flags.tlsCertPath?.let { tls.setClientCertPath(it).setClientCertData(null) }
                flags.tlsKeyPath?.let { tls.setClientKeyPath(it).setClientKeyData(null) }
                flags.tlsCaPath?.let { tls.setServerCACertPath(it).setServerCACertData(null) }
                flags.tlsServerName?.let(tls::setServerName)
                settings.setTls(tls.build())
            }
            return ConnectionSettings(settings.build())
        }

        private fun rootMessage(e: Throwable): String {
            var cause = e
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            return cause.message?.lineSequence()?.first() ?: cause.javaClass.simpleName
        }
    }
}
