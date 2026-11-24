package org.xcore.plugin.permission;

import mindustry.gen.Player;
import mindustry.net.NetConnection;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffEnrollmentsTest {
    private final MovableClock clock = new MovableClock();
    private final StaffEnrollments enrollments = new StaffEnrollments(clock);

    @Test
    void theCodeWorksOnceOnTheConnectionItWasSentTo() {
        Player player = player("uuid-1", "usid-1");
        String code = enrollments.issue(player);

        assertThat(code).hasSize(10);
        assertThat(enrollments.matches(player, code.toLowerCase())).isTrue();
        assertThat(enrollments.consume(player, code)).isTrue();
        assertThat(enrollments.consume(player, code)).isFalse();
        assertThat(enrollments.pending(player)).isFalse();
    }

    @Test
    void anotherConnectionWithTheSameUuidCannotUseIt() {
        Player player = player("uuid-1", "usid-1");
        String code = enrollments.issue(player);

        Player forged = player("uuid-1", "usid-1");
        assertThat(enrollments.matches(forged, code)).isFalse();
        assertThat(enrollments.consume(forged, code)).isFalse();
    }

    @Test
    void aReconnectOrAnotherUsidEndsIt() {
        Player player = player("uuid-1", "usid-1");
        String code = enrollments.issue(player);

        player.con.usid = "usid-2";
        assertThat(enrollments.matches(player, code)).isFalse();

        player.con.usid = "usid-1";
        assertThat(enrollments.pending(player)).as("withdrawn, not just hidden").isFalse();
    }

    @Test
    void itExpires() {
        Player player = player("uuid-1", "usid-1");
        String code = enrollments.issue(player);

        clock.now = clock.now.plus(StaffEnrollments.TTL);
        assertThat(enrollments.matches(player, code)).isFalse();
    }

    @Test
    void wrongCodesWithdrawIt() {
        Player player = player("uuid-1", "usid-1");
        String code = enrollments.issue(player);

        for (int i = 0; i < StaffEnrollments.MAX_WRONG_CODES; i++) {
            assertThat(enrollments.matches(player, "WRONGCODE" + i)).isFalse();
        }
        assertThat(enrollments.matches(player, code)).isFalse();
    }

    @Test
    void aNewCodeReplacesTheOld() {
        Player player = player("uuid-1", "usid-1");
        String first = enrollments.issue(player);
        String second = enrollments.issue(player);

        assertThat(enrollments.matches(player, first)).isEqualTo(first.equals(second));
        assertThat(enrollments.matches(player, second)).isTrue();
    }

    private static Player player(String uuid, String usid) {
        Player player = mock(Player.class);
        when(player.uuid()).thenReturn(uuid);
        NetConnection con = mock(NetConnection.class);
        when(con.isConnected()).thenReturn(true);
        con.usid = usid;
        player.con = con;
        return player;
    }

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
