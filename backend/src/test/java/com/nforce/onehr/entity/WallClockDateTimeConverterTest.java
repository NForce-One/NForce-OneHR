package com.nforce.onehr.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure round-trip tests for the converter behind the checkInAt/checkOutAt/sessionStartedAt
 * timezone fix (see its own Javadoc for the full incident writeup, proven via a raw-JDBC read
 * that bypassed Hibernate entirely). No database involved — these pin down the converter's own
 * offset-normalization contract, so a future edit can't silently reintroduce
 * {@code ZoneId.systemDefault()} dependence.
 */
class WallClockDateTimeConverterTest {

    private final WallClockDateTimeConverter converter = new WallClockDateTimeConverter();

    @Test
    void convertsNullBothWays() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    /**
     * The exact reported incident: DB/stored {@code 2026-09-09 15:51:20.005425+00} must read back
     * as Java {@code 2026-09-09T15:51:20.005425} — never {@code 21:21:20.005425} (the +5:30 skew
     * a JVM defaulting to Asia/Calcutta previously introduced).
     */
    @Test
    void theExactReportedIncident_15_51_20UtcReadsBackAsExactlyItself_neverSkewedBy5h30m() {
        OffsetDateTime stored = OffsetDateTime.of(2026, 9, 9, 15, 51, 20, 5_425_000, ZoneOffset.UTC);

        LocalDateTime entity = converter.convertToEntityAttribute(stored);

        assertThat(entity).isEqualTo(LocalDateTime.of(2026, 9, 9, 15, 51, 20, 5_425_000));
    }

    /**
     * The read side must not depend on which offset the driver happens to hand back for the SAME
     * instant — pgjdbc/Postgres session settings are free to express a TIMESTAMPTZ read in any
     * offset; {@code withOffsetSameInstant(UTC)} must normalize regardless, proving this is
     * genuinely offset-representation-agnostic, not incidentally correct only for a UTC-offset
     * input.
     */
    @Test
    void readIsOffsetRepresentationAgnostic_sameInstantDifferentOffsetGivesTheSameNaiveDigits() {
        OffsetDateTime asKolkataOffset = OffsetDateTime.of(2026, 9, 9, 21, 21, 20, 5_425_000, ZoneOffset.ofHoursMinutes(5, 30));

        LocalDateTime entity = converter.convertToEntityAttribute(asKolkataOffset);

        assertThat(entity).isEqualTo(LocalDateTime.of(2026, 9, 9, 15, 51, 20, 5_425_000));
    }

    @Test
    void writeLabelsTheNaiveDigitsAsUtc_neverTheJvmDefaultZone() {
        LocalDateTime naiveCheckIn = LocalDateTime.of(2026, 9, 9, 15, 51, 20, 5_425_000);

        OffsetDateTime forDatabase = converter.convertToDatabaseColumn(naiveCheckIn);

        assertThat(forDatabase.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(forDatabase.toLocalDateTime()).isEqualTo(naiveCheckIn);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-03-10T09:00:00", "2026-03-10T18:00:00", "2026-09-09T15:51:20.005425",
            "2026-09-09T21:49:55.116022", "2026-01-01T00:00:00", "2026-12-31T23:59:59.999999",
    })
    void roundTripsExactlyWithNoSkew_regardlessOfTimeOfDay(String iso) {
        LocalDateTime original = LocalDateTime.parse(iso);

        OffsetDateTime forDatabase = converter.convertToDatabaseColumn(original);
        LocalDateTime roundTripped = converter.convertToEntityAttribute(forDatabase);

        assertThat(roundTripped).isEqualTo(original);
    }
}
