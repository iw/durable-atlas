package io.github.iw.durableatlas.analyser.code

/**
 * Renders a [Code] tree as indented text, one node per line, for golden files and for looking at
 * what the analysis made of a function. The format is for people; nothing parses it.
 */
object CodePrinter {
    private const val MAX_TEXT = 80

    fun print(code: Code): String = buildString { Printer(this).node(code, 0, null) }

    private class Printer(private val out: StringBuilder) {
        private fun line(depth: Int, text: String) {
            repeat(depth) { out.append("  ") }
            out.append(text).append('\n')
        }

        /** A labelled child: the label on a line of its own, the node below it. */
        private fun child(label: String, code: Code?, depth: Int, file: String) {
            if (code == null) return
            line(depth, label)
            node(code, depth + 1, file)
        }

        fun node(code: Code, depth: Int, parentFile: String?) {
            val file = code.location.file
            // The file is named where it changes, which normally is only at the root.
            val at = if (file == parentFile) ":${code.location.line}" else "$file:${code.location.line}"
            when (code) {
                is Block -> {
                    line(depth, "block $at")
                    code.statements.forEach { node(it, depth + 1, file) }
                }
                is Call -> {
                    val target = code.callee?.let { callee(it) } ?: "?${code.name}"
                    line(depth, "call $target${typeArguments(code.typeArguments)}${type(code.type)} $at")
                    code.receiverType?.let { line(depth + 1, "on ${annotated(it)}") }
                    child("receiver", code.receiver, depth + 1, file)
                    for (argument in code.arguments) {
                        val constant = argument.constant?.let { " = ${constant(it)}" }.orEmpty()
                        line(depth + 1, "argument ${argument.parameter ?: "?"} ${quoted(argument.text)}$constant")
                        node(argument.value, depth + 2, file)
                    }
                }
                is Lambda -> {
                    val form = if (code.isAnonymousFunction) "anonymous-function" else "lambda"
                    val label = code.label?.let { "$it@ " }.orEmpty()
                    line(depth, "$label$form (${code.parameters.joinToString(", ")}) $at")
                    node(code.body, depth + 1, file)
                }
                is CallableReference -> {
                    val target = code.function?.let { callee(it) } ?: code.property?.toString() ?: "?"
                    line(depth, "reference $target ${quoted(code.text)} $at")
                    code.receiverType?.let { line(depth + 1, "on ${annotated(it)}") }
                    child("receiver", code.receiver, depth + 1, file)
                }
                is ClassLiteral -> line(depth, "class-literal ${code.type?.let { annotated(it) } ?: "?"} $at")
                is PropertyRead -> {
                    val constant = code.constant?.let { " = ${constant(it)}" }.orEmpty()
                    line(depth, "read ${code.property ?: "?${code.name}"}${onThis(code.onThis)}${type(code.type)}$constant $at")
                    child("receiver", code.receiver, depth + 1, file)
                }
                is PropertyWrite -> {
                    line(depth, "write ${code.property ?: "?${code.name}"}${onThis(code.onThis)} ${code.operator} $at")
                    child("receiver", code.receiver, depth + 1, file)
                    child("value", code.value, depth + 1, file)
                }
                is LocalRead -> line(depth, "local ${code.name}${type(code.type)} $at")
                is LocalWrite -> {
                    line(depth, "assign ${code.name} ${code.operator} $at")
                    code.value?.let { node(it, depth + 1, file) }
                }
                is LocalVariable -> {
                    line(depth, "${if (code.isMutable) "var" else "val"} ${code.name}${type(code.type)} $at")
                    code.initializer?.let { node(it, depth + 1, file) }
                }
                is Destructuring -> {
                    line(depth, "val (${code.names.joinToString(", ")}) $at")
                    code.initializer?.let { node(it, depth + 1, file) }
                }
                is ThisReference -> line(depth, "this${type(code.type)} $at")
                is ClassReference -> line(depth, "class ${code.name ?: "?${code.text}"} $at")
                is If -> {
                    line(depth, "if ${quoted(code.conditionText)} $at")
                    child("condition", code.condition, depth + 1, file)
                    child("then", code.then, depth + 1, file)
                    child("else", code.otherwise, depth + 1, file)
                }
                is When -> {
                    line(depth, "when ${code.subjectText?.let { quoted(it) } ?: "(no subject)"} $at")
                    child("subject", code.subject, depth + 1, file)
                    for (branch in code.branches) {
                        line(depth + 1, "branch ${quoted(branch.conditionText)} :${branch.location.line}")
                        branch.conditions.forEach { child("condition", it, depth + 2, file) }
                        child("body", branch.body, depth + 2, file)
                    }
                }
                is Loop -> {
                    val label = code.label?.let { "$it@ " }.orEmpty()
                    line(depth, "$label${code.kind.name.lowercase().replace('_', '-')} ${quoted(code.headerText)} $at")
                    child("header", code.header, depth + 1, file)
                    child("body", code.body, depth + 1, file)
                }
                is Try -> {
                    line(depth, "try $at")
                    node(code.body, depth + 1, file)
                    for (catch in code.catches) {
                        val type = catch.type?.let { annotated(it) } ?: "?"
                        line(depth + 1, "catch ${catch.parameter ?: "_"}: $type :${catch.location.line}")
                        node(catch.body, depth + 2, file)
                    }
                    child("finally", code.finally, depth + 1, file)
                }
                is Return -> {
                    line(depth, "return${code.label?.let { "@$it" }.orEmpty()} $at")
                    code.value?.let { node(it, depth + 1, file) }
                }
                is Throw -> {
                    line(depth, "throw${type(code.type)} $at")
                    code.value?.let { node(it, depth + 1, file) }
                }
                is Jump -> line(depth, "${code.kind.name.lowercase()}${code.label?.let { "@$it" }.orEmpty()} $at")
                is Literal -> line(depth, "literal ${constant(code.constant)} $at")
                is Other -> {
                    line(depth, "other${code.operator?.let { " $it" }.orEmpty()} ${quoted(code.text)} $at")
                    code.operands.forEach { node(it, depth + 1, file) }
                }
            }
        }

        private fun callee(callee: Callee): String {
            val origin = if (callee.inSource) "source" else "library"
            val kind = if (callee.kind == CalleeKind.CONSTRUCTOR) "constructor " else ""
            return "$kind${callee.function} [$origin]"
        }

        private fun onThis(onThis: Boolean): String = if (onThis) " on this" else ""

        private fun type(type: TypeRef?): String = type?.let { ": ${it.text}" }.orEmpty()

        private fun typeArguments(arguments: List<TypeRef>): String =
            if (arguments.isEmpty()) "" else arguments.joinToString(", ", " <", ">") { annotated(it) }

        private fun annotated(type: TypeRef): String =
            (listOf(type.text) + type.annotations.map(::annotation)).joinToString(" ")

        private fun annotation(use: AnnotationUse): String {
            val arguments = use.arguments.entries.joinToString(", ") { (name, value) -> "$name = ${constant(value)}" }
            return "@${use.type}" + if (arguments.isEmpty()) "" else "($arguments)"
        }

        private fun constant(constant: Constant): String =
            when (constant) {
                is Constant.Text -> "\"" + escaped(constant.value) + "\""
                is Constant.Integer -> constant.value.toString()
                is Constant.Decimal -> constant.value.toString()
                is Constant.Bool -> constant.value.toString()
                Constant.Null -> "null"
                is Constant.EnumEntry -> "${constant.type}.${constant.entry}"
                is Constant.ClassReference -> "${constant.name}::class"
                is Constant.Array -> constant.elements.joinToString(", ", "[", "]") { constant(it) }
                is Constant.Annotation -> annotation(constant.use)
                is Constant.Unsupported -> "<unsupported ${quoted(constant.text)}>"
            }

        /** Source text on one line, shortened if long. */
        private fun quoted(text: String): String {
            val oneLine = text.replace(WHITESPACE, " ").trim()
            val shortened = if (oneLine.length > MAX_TEXT) oneLine.take(MAX_TEXT - 1) + "…" else oneLine
            return "`$shortened`"
        }

        private fun escaped(text: String): String =
            text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }

    private val WHITESPACE = Regex("\\s+")
}
