package org.xcore.plugin.permission;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import mindustry.gen.Player;
import mindustry.net.NetConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time codes that let a single connection set the first staff password of its account.
 * <p>
 * A uuid proves nothing, since a modified client can send any, so an account with staff grants
 * and no password must not take the first password someone types. The console issues a code to
 * a player it has identified, bound to that player's current connection and USID; only that
 * connection, within {@link #TTL}, can spend it with {@code /staff-setup}. A disconnect makes it
 * useless, and a new code replaces the old one. The code is kept only as a hash and never logged.
 * <p>
 * Codes live in memory: they are bound to a connection of this server, which a restart ends.
 */
@Singleton
public class StaffEnrollments {
    public static final Duration TTL = Duration.ofMinutes(10);
    /** Wrong codes a connection may send before its code is withdrawn. */
    static final int MAX_WRONG_CODES = 5;
    /** No 0/O, 1/I/L: the code is read off the screen and typed into chat. */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 10;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Enrollment> byUuid = new ConcurrentHashMap<>();

    @Inject
    public StaffEnrollments(Clock clock) {
        this.clock = clock;
    }

    /** Issues a code to {@code player}'s current connection, replacing any earlier one. */
    public String issue(Player player) {
        Objects.requireNonNull(player.con, "player is not connected");
        Instant now = clock.instant();
        byUuid.values().removeIf(enrollment -> !now.isBefore(enrollment.expiresAt()));
        char[] code = new char[CODE_LENGTH];
        for (int i = 0; i < code.length; i++) code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        String text = new String(code);
        byUuid.put(player.uuid(), new Enrollment(hash(text), player.con, player.con.usid, now.plus(TTL)));
        return text;
    }

    /** Whether {@code player} has a code waiting on this very connection. */
    public boolean pending(Player player) {
        return live(player) != null;
    }

    /**
     * Whether {@code code} is the live code of {@code player}'s current connection. A wrong code
     * counts against the connection; after {@link #MAX_WRONG_CODES} the code is withdrawn.
     */
    public boolean matches(Player player, String code) {
        Enrollment enrollment = live(player);
        if (enrollment == null) return false;
        if (MessageDigest.isEqual(enrollment.codeHash(), hash(code))) return true;
        if (enrollment.wrongCode() >= MAX_WRONG_CODES) byUuid.remove(player.uuid(), enrollment);
        return false;
    }

    /** Spends the code if it is still the live code of this connection; true at most once. */
    public boolean consume(Player player, String code) {
        Enrollment enrollment = live(player);
        return enrollment != null
                && MessageDigest.isEqual(enrollment.codeHash(), hash(code))
                && byUuid.remove(player.uuid(), enrollment);
    }

    /** Withdraws the player's code, if any. */
    public void withdraw(String uuid) {
        byUuid.remove(uuid);
    }

    private Enrollment live(Player player) {
        if (player == null || player.con == null) return null;
        Enrollment enrollment = byUuid.get(player.uuid());
        if (enrollment == null) return null;
        boolean sameConnection = enrollment.connection() == player.con
                && player.con.isConnected()
                && Objects.equals(enrollment.usid(), player.con.usid);
        if (!sameConnection || !clock.instant().isBefore(enrollment.expiresAt())) {
            byUuid.remove(player.uuid(), enrollment);
            return null;
        }
        return enrollment;
    }

    private static byte[] hash(String code) {
        String normalized = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Enrollment {
        private final byte[] codeHash;
        private final NetConnection connection;
        private final String usid;
        private final Instant expiresAt;
        private int wrongCodes;

        Enrollment(byte[] codeHash, NetConnection connection, String usid, Instant expiresAt) {
            this.codeHash = codeHash;
            this.connection = connection;
            this.usid = usid;
            this.expiresAt = expiresAt;
        }

        byte[] codeHash() {
            return codeHash;
        }

        NetConnection connection() {
            return connection;
        }

        String usid() {
            return usid;
        }

        Instant expiresAt() {
            return expiresAt;
        }

        synchronized int wrongCode() {
            return ++wrongCodes;
        }
    }
}
