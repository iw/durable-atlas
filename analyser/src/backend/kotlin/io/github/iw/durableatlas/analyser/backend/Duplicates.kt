package io.github.iw.durableatlas.analyser.backend

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.iw.durableatlas.ir.SourceLocation
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import org.jetbrains.kotlin.psi.KtEnumEntry

/** One declaration of a class of the sources. */
internal class DeclaredClass(
    /** The class as it is written: a Kotlin class or object, or a Java class. */
    val psi: PsiElement,
    /** Path of the module whose sources hold it. */
    val module: String,
    val location: SourceLocation,
)

/** The code a name is used in: its module, and the classes it is written inside, innermost first. */
internal class Referrer(val module: String?, val classes: List<KtClassOrObject>)

/**
 * The classes that the sources declare more than once under one fully qualified name.
 *
 * A build has no quarrel with that as long as the declarations lie in modules that do not see
 * each other. Here every class is known by its name alone, so one of the declarations stands
 * for the name: the first, a Kotlin one before a Java one and otherwise in the order of the
 * files. Code that means another one would be read with the wrong declaration. And a model that
 * puts the sources of all modules into one has the compiler pick a declaration for each use,
 * with nothing to say that it is the one the code means. So a use of such a name is only
 * trusted where it can only mean the first declaration; see [doubted].
 */
internal class Duplicates(session: AnalysisSession) {
    /** Every declaration of each class name that is declared more than once; the first one stands for the name. */
    val classes: Map<String, List<DeclaredClass>>

    init {
        val declared = LinkedHashMap<String, MutableList<DeclaredClass>>()
        for (file in session.files) {
            for (psi in kotlinClasses(file.psi)) {
                val name = psi.fqName?.asString() ?: continue
                declared.getOrPut(name) { ArrayList() } += DeclaredClass(psi, file.module, SourceLocation(file.path, file.line(psi.textOffset)))
            }
        }
        for (file in session.javaFiles) {
            for (psi in file.psi.classes.flatMap(::withNested)) {
                val name = psi.qualifiedName ?: continue
                declared.getOrPut(name) { ArrayList() } += DeclaredClass(psi, file.module, SourceLocation(file.path, file.line(psi.textOffset)))
            }
        }
        classes = declared.filterValues { it.size > 1 }
    }

    /**
     * [name] if a use of the class of that name, or of a member of it, cannot be trusted to mean
     * the declaration that stands for the name; `null` if it can, or if the name is declared once.
     *
     * It can where the compiler resolved it to the first declaration and nothing else could
     * have been meant: the code that uses it lies inside that declaration, or the module of that
     * code holds no other declaration of the name.
     *
     * @param resolved what the compiler resolved the use to, as it is written in the sources; `null` if it is not of the sources
     */
    fun doubted(name: String, resolved: PsiElement?, from: Referrer): String? {
        val declarations = classes[name] ?: return null
        val first = declarations.first()
        val meansFirst = resolved != null && PsiTreeUtil.isAncestor(first.psi, resolved, false)
        val inside = from.classes.any { it === first.psi }
        val rival = declarations.any { it !== first && it.module == from.module }
        return if (meansFirst && (inside || !rival)) null else name
    }

    private fun kotlinClasses(container: KtDeclarationContainer): List<KtClassOrObject> =
        container.declarations
            .filterIsInstance<KtClassOrObject>()
            .filter { it !is KtEnumEntry }
            .flatMap { listOf(it) + kotlinClasses(it) }

    private fun withNested(declared: PsiClass): List<PsiClass> = listOf(declared) + declared.innerClasses.flatMap(::withNested)
}
