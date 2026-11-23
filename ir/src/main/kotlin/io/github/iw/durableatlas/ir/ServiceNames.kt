package io.github.iw.durableatlas.ir

import java.net.URI

/**
 * Names the services that requests go to, as [Request.service] gives them.
 *
 * A call of a client goes to the service named after the client. An HTTP request goes to the
 * host of the first value configured for the key its target starts with (the value without a
 * profile, then that of `prod`) that names a host other than this machine or an address; a
 * `${…}` expression stands for its default there. The service is named after the first label
 * of that host, or as many labels as it takes to tell it from the others. A request whose host
 * is not known is named after the key its target starts with, or the name or text it starts with.
 */
object ServiceNames {
    /** The name of the service of each of [requests], by request; requests that are equal share it. */
    fun of(requests: Collection<Request>): Map<Request, String> {
        val identities = requests.associateWith(::identity)
        val names = names(identities.values.toSortedSet())
        return identities.mapValues { names.getValue(it.value) }
    }

    /** What tells a service apart, as a text: its kind, a colon, and what it is known by. */
    internal fun identity(request: Request): String {
        if (request.kind == RequestKind.CLIENT) return "$CLIENT${request.client ?: request.call ?: "client"}"
        val values = listOfNotNull(request.host) + request.hosts
            .filter { it.key == request.keys.firstOrNull() }
            .sortedBy { if (it.profile == null) 0 else 1 }
            .map { it.value }
        val hosts = values.mapNotNull(::hostOf)
        hosts.firstOrNull { !isLocal(it) }?.let { return "$HOST$it" }
        val target = request.target.orEmpty()
        val first = request.keys.firstOrNull()?.takeIf { target.startsWith("{$it}") }
        if (first != null) return "$KEY$first"
        hostOf(target)?.takeUnless(::isLocal)?.let { return "$HOST$it" }
        val written = Regex("^\\{([^}]*)}").find(target)?.groupValues?.get(1)
        return "$NAME${written ?: target.substringBefore('/').ifEmpty { "unknown" }}"
    }

    private fun names(identities: Set<String>): Map<String, String> {
        val labels = identities.associateWith { identity -> if (identity.startsWith(HOST)) identity.removePrefix(HOST).split('.') else emptyList() }
        val taken = HashMap<String, Int>()
        val used = identities.associateWith { 1 }.toMutableMap()
        fun name(identity: String): String {
            val parts = labels.getValue(identity)
            return if (parts.isEmpty()) identity.substringAfter(':') else parts.take(used.getValue(identity)).joinToString(".")
        }
        // Hosts that share a name are named by one more label each, until they share none or have no more.
        while (true) {
            val clashing = identities.groupBy(::name).values.filter { group -> group.size > 1 && group.any { used.getValue(it) < labels.getValue(it).size } }
            if (clashing.isEmpty()) break
            for (group in clashing) for (identity in group) used[identity] = minOf(used.getValue(identity) + 1, labels.getValue(identity).size.coerceAtLeast(1))
        }
        val result = LinkedHashMap<String, String>()
        for (identity in identities) {
            val base = name(identity)
            val count = taken.merge(base.lowercase(), 1, Int::plus)!!
            result[identity] = if (count == 1) base else "$base-$count"
        }
        return result
    }

    /** The host a configured value names, with each `${…}` expression standing for its default; `null` if it names none. */
    fun hostOf(value: String): String? {
        val expanded = defaults(value) ?: return null
        val text = if ("://" in expanded) expanded else "http://$expanded"
        val host = runCatching { URI(text.substringBefore('{').trim()).host }.getOrNull() ?: return null
        return host.lowercase().removeSuffix(".").takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
    }

    /** [value] with each `${name:default}` replaced by its default; `null` where one has none. */
    private fun defaults(value: String): String? {
        var text = value
        repeat(MAX_EXPANSIONS) {
            val start = text.lastIndexOf("\${")
            if (start < 0) return text
            val end = text.indexOf('}', start)
            if (end < 0) return null
            val inner = text.substring(start + 2, end)
            if (':' !in inner) return null
            text = text.substring(0, start) + inner.substringAfter(':') + text.substring(end + 1)
        }
        return null
    }

    /** A host that is this machine, or an address: no name of a service. */
    private fun isLocal(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.all { it.isDigit() || it == '.' } || host.startsWith("[") || ':' in host

    private const val CLIENT = "client:"
    private const val HOST = "host:"
    private const val KEY = "key:"
    private const val NAME = "name:"
    private const val MAX_EXPANSIONS = 16
}
