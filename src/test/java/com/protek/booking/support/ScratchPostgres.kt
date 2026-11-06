package com.protek.booking.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.function.UnaryOperator

/**
 * A standalone Testcontainers PostgreSQL 18.6 instance, independent of Quarkus Dev
 * Services' shared container — used only by the differential tests, which need to
 * apply a deliberately *weakened* schema (a dropped constraint, in this case) without
 * touching the real, shared schema every other test runs against.
 *
 * `@Throws` on the JDBC methods keeps them declaring what the Java did, so a Java caller's
 * `catch (SQLException e)` still compiles.
 */
class ScratchPostgres private constructor(private val container: GenericContainer<*>) : AutoCloseable {

    companion object {
        @JvmStatic
        fun start(): ScratchPostgres {
            val c: GenericContainer<*> = GenericContainer(DockerImageName.parse("postgres:18.6"))
                .withEnv("POSTGRES_PASSWORD", "test")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s*$", 2)
                    .withStartupTimeout(Duration.ofSeconds(60)))
            c.start()
            return ScratchPostgres(c)
        }
    }

    fun jdbcUrl(): String =
        "jdbc:postgresql://" + container.host + ":" + container.getMappedPort(5432) + "/postgres"

    @Throws(SQLException::class)
    fun connect(): Connection = DriverManager.getConnection(jdbcUrl(), "postgres", "test")

    /** Reads a classpath migration resource, optionally transforms its text (e.g. to strip
     * out one constraint for a differential test), and applies it as one statement batch —
     * PostgreSQL's simple query protocol accepts a semicolon-separated script directly. */
    @Throws(Exception::class)
    fun applySchema(classpathResource: String, transform: UnaryOperator<String>?) {
        var sql: String
        javaClass.classLoader.getResourceAsStream(classpathResource).use { input ->
            if (input == null) {
                throw IllegalArgumentException("Migration resource not found: " + classpathResource)
            }
            sql = String(input.readAllBytes(), StandardCharsets.UTF_8)
        }
        if (transform != null) {
            sql = transform.apply(sql)
        }
        connect().use { c -> c.createStatement().use { st -> st.execute(sql) } }
    }

    override fun close() {
        container.stop()
    }
}
