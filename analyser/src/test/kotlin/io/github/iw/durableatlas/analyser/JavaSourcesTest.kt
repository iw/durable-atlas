package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.inventory.Inventory
import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.analyser.inventory.StandaloneActivitiesJson
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.SourceLocation
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A module that keeps interfaces in Java and implements its workflow in Kotlin. What the Java
 * files declare is listed with its place, like what Kotlin files declare; their code is not read.
 */
class JavaSourcesTest {
    private fun analyse(directory: Path): AnalysisResult {
        val sources = SyntheticSources(directory.resolve("model"))
        sources.javaFile(
            ":app",
            "mixed/Booking.java",
            """
            package mixed;

            import io.temporal.workflow.WorkflowInterface;
            import io.temporal.workflow.WorkflowMethod;

            @WorkflowInterface
            public interface Booking {
              @WorkflowMethod
              String book(String name);
            }
            """,
        )
        sources.javaFile(
            ":app",
            "mixed/Desk.java",
            """
            package mixed;

            import io.temporal.activity.ActivityInterface;

            /** Reserves desks. */
            @ActivityInterface
            public interface Desk {
              String reserve(String name);

              void release(String id);
            }
            """,
        )
        sources.javaFile(
            ":app",
            "mixed/DeskImpl.java",
            """
            package mixed;

            import io.temporal.activity.ActivityInterface;

            public class DeskImpl implements Desk {
              @Override
              public String reserve(String name) {
                return name;
              }

              @Override
              public void release(String id) {}

              /** An interface declared inside a class is found as well. */
              @ActivityInterface
              public interface Cleaning {
                void sweep();
              }
            }
            """,
        )
        // A workflow written in Java is not read, and so is not listed as an implementation.
        sources.javaFile(
            ":app",
            "mixed/OldBooking.java",
            """
            package mixed;

            public class OldBooking implements Booking {
              @Override
              public String book(String name) {
                return name;
              }
            }
            """,
        )
        sources.file(
            ":app",
            "mixed/BookingImpl.kt",
            """
            package mixed

            import io.temporal.activity.ActivityOptions
            import io.temporal.workflow.Workflow
            import java.time.Duration

            class BookingImpl : Booking {
                private val desk = Workflow.newActivityStub(
                    Desk::class.java,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build(),
                )

                override fun book(name: String): String = desk.reserve(name)
            }
            """,
        )
        return Analyser().analyse(AnalysisRequest(model = sources.model().directory, out = directory.resolve("out")))
    }

    @Test
    fun `a workflow interface written in Java has its module and its place`(@TempDir directory: Path) {
        val result = analyse(directory)
        val workflow = result.inventory.workflows.single()

        assertEquals("Booking", workflow.workflowType)
        assertEquals("mixed.Booking", workflow.interfaceName)
        assertEquals(":app", workflow.module)
        assertEquals(SourceLocation("app/src/main/java/mixed/Booking.java", 7), workflow.loc)
        assertEquals(listOf("mixed.BookingImpl"), workflow.implementations.map { it.className })
        assertEquals(result.inventory, InventoryJson.decode(Files.readString(result.inventoryFile)))
    }

    @Test
    fun `an activity interface written in Java is listed with what implements it`(@TempDir directory: Path) {
        val inventory: Inventory = analyse(directory).inventory

        assertEquals(listOf("mixed.Desk", "mixed.DeskImpl.Cleaning"), inventory.activityInterfaces.map { it.interfaceName })
        val desk = inventory.activityInterfaces.first()
        assertEquals(":app", desk.module)
        assertEquals(SourceLocation("app/src/main/java/mixed/Desk.java", 7), desk.loc)
        assertEquals(listOf("Release release", "Reserve reserve"), desk.activities.map { "${it.name} ${it.method}" })
        val implementation = desk.implementations.single()
        assertEquals("mixed.DeskImpl", implementation.className)
        assertEquals(":app", implementation.module)
        assertEquals(SourceLocation("app/src/main/java/mixed/DeskImpl.java", 5), implementation.loc)

        val cleaning = inventory.activityInterfaces.last()
        assertEquals(SourceLocation("app/src/main/java/mixed/DeskImpl.java", 16), cleaning.loc)
        assertEquals(listOf("Sweep"), cleaning.activities.map { it.name })
    }

    @Test
    fun `the workflow in Kotlin calls the activity the Java interface declares, and the others are listed with their lines`(@TempDir directory: Path) {
        val result = analyse(directory)
        val document = IrJson.decode(Files.readString(result.workflows.single().file))

        assertEquals(listOf("activity Reserve timeout PT10S", "end"), document.steps())
        assertEquals("mixed.Booking", document.interfaceName)
        assertEquals(":app", document.module)

        val standalone = result.standaloneActivities
        assertEquals(standalone, StandaloneActivitiesJson.decode(Files.readString(result.standaloneActivitiesFile)))
        assertEquals(
            listOf(
                "Release mixed.Desk app/src/main/java/mixed/Desk.java:10",
                "Sweep mixed.DeskImpl.Cleaning app/src/main/java/mixed/DeskImpl.java:17",
            ),
            standalone.activities.map { "${it.name} ${it.interfaceName} ${it.loc?.file}:${it.loc?.line}" },
        )
        assertEquals(emptyList(), result.warnings)
        assertEquals(emptyList(), result.discovery.warnings)
    }

    @Test
    fun `sources with Java files alone are opened, say that no workflow is drawn, and list what the files declare`(@TempDir directory: Path) {
        val log = ArrayList<String>()
        val sources = SyntheticSources(directory)
            .javaFile(
                ":app",
                "only/Desk.java",
                """
                package only;

                import io.temporal.activity.ActivityInterface;

                @ActivityInterface
                public interface Desk {
                  String reserve(String name);
                }
                """,
            )
            .javaFile(
                ":app",
                "only/Booking.java",
                """
                package only;

                import io.temporal.workflow.WorkflowInterface;
                import io.temporal.workflow.WorkflowMethod;

                @WorkflowInterface
                public interface Booking {
                  @WorkflowMethod
                  String book(String name);
                }
                """,
            )
        // A module's directory for Kotlin sources is named whether or not it holds any.
        val result = Analyser(log::add).analyse(AnalysisRequest(model = sources.model().directory, out = directory.resolve("out")))

        assertEquals(
            listOf(
                "warning: the sources hold 2 Java file(s) and no Kotlin file. Only code written in Kotlin is analysed, " +
                    "so no workflow is drawn; what the Java files declare is listed in the inventory",
            ),
            log.filter { "Java file" in it },
        )
        assertEquals(emptyList(), result.inventory.workflows)
        assertEquals(listOf("only.Desk"), result.inventory.activityInterfaces.map { it.interfaceName })
        assertEquals(emptyList(), result.workflows)
    }
}
