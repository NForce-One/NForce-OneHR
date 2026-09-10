package com.nforce.onehr.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Binds {@link LocalDateTime} to a Postgres {@code TIMESTAMPTZ} column as an explicit-UTC-offset
 * {@link OffsetDateTime}, instead of Hibernate's default {@code LocalDateTime <-> TIMESTAMPTZ}
 * binding — the {@link LocalTimeTextConverter}-shaped fix for the {@code LocalDateTime} analogue
 * of the exact same incident class that converter's own Javadoc describes for {@link
 * java.time.LocalTime}/{@code TIME} columns.
 *
 * <p><b>The incident:</b> {@code Attendance.checkInAt}/{@code checkOutAt}/{@code
 * sessionStartedAt} are naive wall-clock digits by design — the employee's actual local check-in
 * time, with no zone attached in Java, matching every other naive-wall-clock convention this app
 * already relies on (see the frontend's own {@code formatTimeAs} comment: "Backend LocalDateTime
 * strings are naive wall-clock digits already in the record's own resolved zone... there is
 * nothing left to convert"). Writing them is correctly zone-independent — the naive digits are
 * stored verbatim, UTC-labeled. But a direct empirical test (raw JDBC, no Hibernate involved)
 * proved the READ side is NOT: {@code ResultSet.getObject(col, LocalDateTime.class)} on a {@code
 * TIMESTAMPTZ} column throws outright ("Cannot convert the column of type TIMESTAMPTZ to
 * requested type java.time.LocalDateTime") — pgjdbc has no zone-free way to do this conversion at
 * all, because a real instant has no "local" rendering without picking a zone. Whatever fallback
 * path Hibernate takes instead ends up projecting through the JVM's own default zone: a value
 * genuinely stored as {@code 2026-09-09 15:51:20+00} (a real Kolkata-local check-in, correctly
 * UTC-labeled at write time) read back on this app's own dev machine (JVM default {@code
 * Asia/Calcutta}) as {@code 2026-09-09T21:21:20} — corrupted by exactly the JVM's own +5:30
 * offset. {@code app.attendance} config's {@code hibernate.jdbc.time_zone: UTC} setting, meant to
 * prevent exactly this, does not reach this binding path.
 *
 * <p><b>The fix:</b> never let Hibernate materialize a {@code LocalDateTime} directly from a
 * {@code TIMESTAMPTZ} column. {@link OffsetDateTime} is the type pgjdbc DOES natively and
 * correctly support for {@code TIMESTAMPTZ} (an unambiguous instant, self-describing offset, no
 * driver/session/JVM zone guessing involved) — so this converter routes every read/write through
 * one, doing the naive-digits interpretation itself in pure Java, with an explicit {@link
 * ZoneOffset#UTC}, never {@code ZoneId.systemDefault()}:
 * <ul>
 *   <li>Write: the naive {@code LocalDateTime} is labeled UTC ({@code atOffset(UTC)}) before
 *       binding — the exact same "naive digits, UTC-labeled" contract the column already holds,
 *       made explicit and JVM-zone-independent instead of incidentally correct only when the
 *       writing JVM's own default happens to already be UTC (true in production/Railway, not
 *       guaranteed on every dev machine).</li>
 *   <li>Read: whatever offset pgjdbc hands back is normalized to UTC ({@code
 *       withOffsetSameInstant(UTC)}) — an instant-preserving, purely mathematical transform, not a
 *       zone lookup — before taking the local date/time fields. Since the column's stored instant
 *       already represents "naive digits, UTC-labeled," normalizing to UTC and reading off the
 *       local fields is a no-op on the original digits, regardless of what offset the driver
 *       happened to choose to express it in, and regardless of the reading JVM's own default
 *       zone.</li>
 * </ul>
 * Existing rows need no migration: the stored instant is untouched by this converter (proven a
 * pure identity transform on it above) — only how it is subsequently materialized into Java
 * changes, from "wrong on any non-UTC-default JVM" to "correct everywhere."
 */
@Converter
public class WallClockDateTimeConverter implements AttributeConverter<LocalDateTime, OffsetDateTime> {

    @Override
    public OffsetDateTime convertToDatabaseColumn(LocalDateTime attribute) {
        return attribute == null ? null : attribute.atOffset(ZoneOffset.UTC);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(OffsetDateTime dbData) {
        return dbData == null ? null : dbData.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
