package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.RateRule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Pricing lives in jsonb, not rigid columns. Two shapes are written and read:
 * the flat rate `{"type":"flat","amount":<number>}` and the calendar pricing's
 * `by_calendar` day-of-week bands — see [com.protek.booking.service.RatePlan],
 * which is the only thing that interprets the column. */
@ApplicationScoped
class RateRuleRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, resourcePoolId: UUID?,
               ruleDefinitionJson: String?, effectiveFrom: Instant?): RateRule =
        db.queryOne(c,
            "INSERT INTO rate_rule (tenant_id, resource_pool_id, rule_definition, effective_from) " +
                    "VALUES (?, ?, ?::jsonb, ?) RETURNING " + COLUMNS,
            ::map, tenantId, resourcePoolId, ruleDefinitionJson, effectiveFrom
        ).orElseThrow()

    /** The rule in force right now — what a hold being taken now is priced by. */
    fun findActive(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): Optional<RateRule> =
        findActiveAt(c, tenantId, resourcePoolId, Instant.now())

    /**
     * The rule that was in force at `at` — what an *existing* booking was priced
     * by, however many times the tenant has changed its prices since.
     *
     * Needed because a rule now carries more than a number: calendar pricing's `inclusions` line
     * (a cinema may bundle a drink voucher into every ticket) is a promise printed on a ticket, and
     * reading it from today's rule would quietly restate last month's ticket in this month's
     * terms. The amount never had this problem — it is fixed on `reservation` at hold
     * time — and this is what lets the inclusions be as honest without a second column
     * to keep in step with it. Sound only because `rate_rule` is append-only: [insert] adds a
     * superseding row rather than updating one.
     *
     * Note what this is *not*: the band within a calendar rule is chosen by the
     * showing's start, not by either of these instants. Which rule applies and which band of
     * it applies are two different questions about two different times, and collapsing them
     * prices a Monday matinee bought on Saturday at the weekend rate.
     */
    fun findActiveAt(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, at: Instant?): Optional<RateRule> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM rate_rule " +
                    "WHERE tenant_id = ? AND resource_pool_id = ? " +
                    "AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?) " +
                    "ORDER BY effective_from DESC LIMIT 1",
            ::map, tenantId, resourcePoolId, at, at)

    private fun map(rs: ResultSet): RateRule {
        val to = rs.getTimestamp("effective_to")
        return RateRule(
            rs.getObject("rate_rule_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("resource_pool_id", UUID::class.java),
            rs.getString("rule_definition"),
            rs.getTimestamp("effective_from").toInstant(),
            to?.toInstant(),
        )
    }

    private companion object {
        const val COLUMNS =
            "rate_rule_id, tenant_id, resource_pool_id, rule_definition::text, effective_from, effective_to"
    }
}
