package io.github.iw.durableatlas.analyser.requests

/**
 * A client of the analysed repository's own whose calls are requests, as `--client` names it:
 * `[Type.]method[:parameter]`. A call of [method] is one of the client where it is made on an
 * object of [type], or on any object where no type is given. What it asks for is the argument
 * given to [parameter], or to the parameter at [position].
 *
 * The form is a dotted name, as Kotlin writes a member, with the parameter after a colon. It
 * holds no character that a shell gives a meaning to, so that it needs no quotes.
 */
data class ClientSpec(
    /**
     * The class of the client: its simple name, or its fully qualified name, which names it
     * alone. `null` for a client of any class.
     */
    val type: String?,
    /** The name of the function that is called. */
    val method: String,
    /** The name of the parameter whose argument says what is asked for; `null` if [position] says it, or nothing does. */
    val parameter: String? = null,
    /** The place, from 1, of the argument that says what is asked for, where it is named by its place. */
    val position: Int? = null,
) {
    /** The class of the client without its package, as requests name it. */
    val simpleType: String? get() = type?.substringAfterLast('.')

    override fun toString(): String =
        listOfNotNull(type, method).joinToString(".") + (parameter ?: position?.toString())?.let { ":$it" }.orEmpty()

    companion object {
        /** What `--client` expects, in words. */
        const val FORM: String = "[Type.]method[:parameter]"

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val POSITION = Regex("[1-9][0-9]{0,2}")

        /**
         * Reads [text] in the form [FORM]. A parameter that is a number names the argument at
         * that place.
         *
         * @throws IllegalArgumentException with a message that says what is wrong
         */
        fun parse(text: String): ClientSpec {
            val call = text.substringBefore(':')
            val argument = if (':' in text) text.substringAfter(':') else null
            val names = call.split('.')
            require(call.isNotEmpty() && names.all { IDENTIFIER.matches(it) }) {
                "'$text' does not name a client: write $FORM, for example ServiceClient.invoke:functionName"
            }
            if (argument == null) return ClientSpec(type(names), names.last())
            return when {
                POSITION.matches(argument) -> ClientSpec(type(names), names.last(), position = argument.toInt())
                IDENTIFIER.matches(argument) -> ClientSpec(type(names), names.last(), parameter = argument)
                else -> throw IllegalArgumentException(
                    "'$text' does not name a client: after ':' comes the name of a parameter, or the place of an argument from 1",
                )
            }
        }

        private fun type(names: List<String>): String? = names.dropLast(1).joinToString(".").ifEmpty { null }
    }
}
