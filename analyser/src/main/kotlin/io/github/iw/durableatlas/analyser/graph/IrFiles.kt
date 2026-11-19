package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.ir.IrDocument

/**
 * Names of the files IR documents are written to.
 *
 * A document goes to `<workflow type>.ir.json`. Workflow types are free text, so characters other
 * than letters, digits, `.`, `_` and `-` become `_`. When that gives several documents the same
 * name (two interfaces with one simple name, two classes implementing one interface, or names
 * that differ only in case, which some file systems cannot tell apart), each of them is named
 * `<workflow type>--<implementing class>.ir.json` instead, with the class fully qualified. Should
 * names still clash, the later ones in the order of the documents get `-2`, `-3` and so on.
 *
 * Nothing but this scheme ties a name to a document: readers take the workflow type, interface
 * and class from the document itself.
 */
object IrFiles {
    /** What the name of every IR file ends in. */
    const val SUFFIX: String = ".ir.json"

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /** True if [fileName] is the name of an IR file. */
    fun isIrFile(fileName: String): Boolean = fileName.endsWith(SUFFIX) && fileName.length > SUFFIX.length

    /** A file name for each of [documents], in their order. No two are equal, whatever the case of their letters. */
    fun names(documents: List<IrDocument>): List<String> {
        val plain = documents.map { safe(it.workflowType) }
        val shared = plain.groupingBy(::key).eachCount().filterValues { it > 1 }.keys
        val taken = HashSet<String>()
        return documents.mapIndexed { index, document ->
            val base = if (key(plain[index]) in shared) "${plain[index]}--${safe(document.implementation)}" else plain[index]
            var name = base
            var count = 1
            while (!taken.add(key(name))) name = "$base-${++count}"
            name + SUFFIX
        }
    }

    /** What two names must differ in to be different files everywhere. */
    private fun key(name: String): String = name.lowercase()

    private fun safe(text: String): String = text.replace(UNSAFE, "_").trimStart('.').ifEmpty { "_" }
}
