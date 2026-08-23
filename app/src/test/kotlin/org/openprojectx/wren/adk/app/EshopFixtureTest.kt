package org.openprojectx.wren.adk.app

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the e-shop fixture itself. If these fail, every downstream assertion
 * about revenue or joins is meaningless — so they run first and without Wren.
 */
class EshopFixtureTest {

    private fun connect(): Connection =
        DriverManager.getConnection(
            EshopContainers.postgres.jdbcUrl,
            EshopContainers.postgres.username,
            EshopContainers.postgres.password,
        )

    private fun <T> query(sql: String, extract: (java.sql.ResultSet) -> T): T =
        connect().use { c ->
            c.createStatement().use { st ->
                st.execute("SET search_path TO wrenai")
                st.executeQuery(sql).use { rs -> rs.next(); extract(rs) }
            }
        }

    @Test
    fun `all eight tables are loaded`() {
        val expected = mapOf(
            "categories" to 8, "products" to 64, "customers" to 40,
            "addresses" to 47, "orders" to 212, "order_items" to 479,
            "payments" to 200, "reviews" to 70,
        )
        expected.forEach { (table, rows) ->
            assertEquals(rows, query("SELECT count(*) FROM $table") { it.getInt(1) }, "row count for $table")
        }
    }

    @Test
    fun `order totals reconcile to line items plus shipping`() {
        val mismatches = query(
            """
            SELECT count(*) FROM orders o
            JOIN (SELECT order_id, sum(line_total) s FROM order_items GROUP BY 1) i
              ON i.order_id = o.order_id
            WHERE round(i.s + o.shipping_fee, 2) <> o.total_amount
            """,
        ) { it.getInt(1) }
        assertEquals(0, mismatches, "order totals must reconcile to their line items")
    }

    @Test
    fun `no orphaned foreign keys`() {
        val orphans = query(
            """
            SELECT
              (SELECT count(*) FROM order_items li
                 LEFT JOIN orders o ON o.order_id = li.order_id WHERE o.order_id IS NULL)
            + (SELECT count(*) FROM orders o
                 LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE c.customer_id IS NULL)
            + (SELECT count(*) FROM products p
                 LEFT JOIN categories k ON k.category_id = p.category_id WHERE k.category_id IS NULL)
            """,
        ) { it.getInt(1) }
        assertEquals(0, orphans, "fixture must be referentially intact")
    }

    @Test
    fun `realised revenue is a plausible positive figure`() {
        val revenue = query(
            "SELECT sum(total_amount) FROM orders WHERE status IN ('delivered','shipped')",
        ) { it.getBigDecimal(1) }
        assertTrue(revenue > BigDecimal.ZERO, "realised revenue should be positive, was $revenue")
    }

    @Test
    fun `status values match the documented enum`() {
        val unknown = query(
            """
            SELECT count(*) FROM orders
            WHERE status NOT IN ('pending','processing','shipped','delivered','cancelled','returned')
            """,
        ) { it.getInt(1) }
        assertEquals(0, unknown, "unexpected order status in the fixture")
    }
}
