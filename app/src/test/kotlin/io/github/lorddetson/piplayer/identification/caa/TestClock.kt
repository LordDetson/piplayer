package io.github.lorddetson.piplayer.identification.caa

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A clock the test moves by hand, for failure TTLs. */
class TestClock(@Volatile var now: Instant = Instant.parse("2026-11-15T12:00:00Z")) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by.toJavaDuration())
    }

    fun after(by: Duration): Instant = now.plus(by.toJavaDuration())
}
