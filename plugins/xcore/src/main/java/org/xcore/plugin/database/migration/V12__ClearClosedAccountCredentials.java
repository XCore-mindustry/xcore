package org.xcore.plugin.database.migration;

import arc.util.Log;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Migration v12: gives every closed account (uuid {@code merged:...}) the credentials an account
 * merge leaves it, as protocol/storage/merges/README.md defines them. Merges done from Discord
 * before 5.10.0 kept the source's username, password hash, device tokens and admin flag on it.
 * <p>
 * Only those credential fields of closed accounts are written, and only where they still hold
 * something, so a second run changes nothing. A username freed here goes to the account it was
 * merged into when that account has none and two records agree on which account it is (see
 * {@link #survivor}). Device tokens the merge copied to that account stay; the log names the
 * accounts holding them.
 */
@Singleton
public class V12__ClearClosedAccountCredentials implements Migration {
    static final String PLAYERS = "players";
    static final String MERGED_PREFIX = "merged:";
    private static final Pattern MERGED_INTO = Pattern.compile("^Merged into PID #(-?\\d+) \\(");
    private static final Bson CLOSED = Filters.regex("uuid", "^" + Pattern.quote(MERGED_PREFIX));

    /**
     * What a run did.
     *
     * @param accounts         closed accounts that had something cleared
     * @param fields           how many closed accounts had each field cleared
     * @param usernamesMoved   "#closed -> #survivor: name" for each username given to the account it was merged into
     * @param usernamesDropped "#closed: name (why)" for each username cleared without a new owner
     * @param copiedTokens     "#survivor: n from #closed" for each live account holding device tokens of a closed one
     */
    record Report(int accounts, Map<String, Integer> fields, List<String> usernamesMoved, List<String> usernamesDropped,
                  List<String> copiedTokens) {
        boolean changedNothing() {
            return accounts == 0 && usernamesMoved.isEmpty();
        }
    }

    @Override
    public int getVersion() {
        return 12;
    }

    @Override
    public String getDescription() {
        return "Clear the credentials that Discord merges before 5.10.0 left on closed accounts";
    }

    @Override
    public void up(MongoDatabase database) {
        var report = apply(database);
        Log.info("[Migrations v12] Cleared credentials of @ closed accounts: @.", report.accounts(), report.fields());
        report.usernamesMoved().forEach(line -> Log.info("[Migrations v12] Username moved to the surviving account: @", line));
        report.usernamesDropped().forEach(line -> Log.info("[Migrations v12] Username freed, no new owner: @", line));
        report.copiedTokens().forEach(line -> Log.warn(
                "[Migrations v12] Holds device tokens copied from a closed account (left in place; a staff password reset revokes them): @",
                line));
    }

    Report apply(MongoDatabase database) {
        MongoCollection<Document> players = database.getCollection(PLAYERS);
        List<Document> closed = players.find(CLOSED).into(new ArrayList<>());

        // Read before anything is cleared: the closed accounts' hashes are the only record of what was copied.
        List<String> copiedTokens = copiedTokens(players, closed);

        Map<String, Integer> fields = new TreeMap<>();
        int accounts = 0;
        Map<Object, List<Claim>> claims = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        for (Document account : closed) {
            Document cleared = clearedFields(account);
            if (cleared.isEmpty()) continue;
            players.updateOne(Filters.eq("_id", account.get("_id")), new Document("$set", cleared));
            accounts++;
            cleared.keySet().forEach(field -> fields.merge(field, 1, Integer::sum));

            if (filled(account.get("username"))) {
                var name = account.getString("username");
                var survivor = survivor(database, account);
                if (survivor == null) {
                    dropped.add(label(account) + ": " + name + " (the account it was merged into is not certain)");
                } else if (filled(survivor.get("username"))) {
                    dropped.add(label(account) + ": " + name + " (" + label(survivor) + " has its own)");
                } else {
                    claims.computeIfAbsent(survivor.get("_id"), id -> new ArrayList<>()).add(new Claim(account, survivor, name));
                }
            }
        }

        List<String> moved = new ArrayList<>();
        for (var claim : claims.values()) {
            if (claim.size() > 1) {
                // Two closed accounts offer their names to one account: which one it chose is unknown.
                claim.forEach(c -> dropped.add(label(c.closed()) + ": " + c.name() + " (" + label(c.survivor())
                        + " was given several; it keeps none)"));
                continue;
            }
            var only = claim.get(0);
            try {
                var result = players.updateOne(Filters.and(Filters.eq("_id", only.survivor().get("_id")),
                                Filters.or(Filters.eq("username", ""), Filters.exists("username", false))),
                        Updates.set("username", only.name()));
                if (result.getModifiedCount() == 1) {
                    moved.add(label(only.closed()) + " -> " + label(only.survivor()) + ": " + only.name());
                } else {
                    dropped.add(label(only.closed()) + ": " + only.name() + " (" + label(only.survivor()) + " took a name meanwhile)");
                }
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw e;
                dropped.add(label(only.closed()) + ": " + only.name() + " (another account holds it)");
            }
        }
        return new Report(accounts, fields, moved, dropped, copiedTokens);
    }

    private record Claim(Document closed, Document survivor, String name) {
    }

    /** The credential fields of a closed account that still hold something, set to what the merge leaves. */
    static Document clearedFields(Document account) {
        var set = new Document();
        for (var field : List.of("username", "password_hash", "discord_id", "discord_username")) {
            if (filled(account.get(field))) set.put(field, "");
        }
        for (var field : List.of("can_change_username", "is_admin")) {
            if (Boolean.TRUE.equals(account.get(field))) set.put(field, false);
        }
        if (account.get("admin_source") instanceof String source && !source.equals("NONE")) set.put("admin_source", "NONE");
        if (account.get("device_tokens") instanceof Document tokens && !tokens.isEmpty()) set.put("device_tokens", new Document());
        if (account.get("device_token_hashes") instanceof List<?> hashes && !hashes.isEmpty()) {
            set.put("device_token_hashes", new ArrayList<String>());
        }
        return set;
    }

    /**
     * The live account a closed one was merged into, or null unless two records agree. Both merges
     * write "Merged into PID #n" into the closed account's description; the account with that PID
     * counts only if a MERGE audit entry or the closed account's statistics redirect name it too.
     * A PID can have moved since (keep_pid, or a later merge), and either record can be missing.
     */
    static @Nullable Document survivor(MongoDatabase database, Document closed) {
        if (!(closed.get("description") instanceof String description)) return null;
        var match = MERGED_INTO.matcher(description);
        if (!match.find()) return null;
        int pid;
        try {
            pid = Integer.parseInt(match.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        var candidates = database.getCollection(PLAYERS)
                .find(Filters.and(Filters.eq("pid", pid), Filters.not(CLOSED))).limit(2).into(new ArrayList<>());
        if (candidates.size() != 1 || !(candidates.get(0).get("uuid") instanceof String uuid)) return null;

        String source = closed.getString("uuid").substring(MERGED_PREFIX.length());
        boolean audited = database.getCollection("moderation_audit").countDocuments(Filters.and(
                Filters.eq("action", "MERGE"), Filters.eq("target.uuid", uuid),
                Filters.or(Filters.eq("details.extra.source_uuid", source),
                        Filters.eq("details.extra.merge.source_uuid", source)))) > 0;
        return audited || uuid.equals(redirectEnd(database, source)) ? candidates.get(0) : null;
    }

    /** Where player_totals redirects follow from {@code uuid}, as the statistics readers follow them. */
    private static @Nullable String redirectEnd(MongoDatabase database, String uuid) {
        var totals = database.getCollection("player_totals");
        var seen = new HashSet<String>();
        String current = uuid;
        while (seen.add(current)) {
            var doc = totals.find(Filters.eq("_id", current)).first();
            if (doc == null || !(doc.get("redirect_uuid") instanceof String next) || next.isBlank()) {
                return current.equals(uuid) ? null : current;
            }
            current = next;
        }
        return null;
    }

    /** "#survivor: n from #closed" for every live account holding a device token hash of a closed one. */
    private static List<String> copiedTokens(MongoCollection<Document> players, Collection<Document> closed) {
        List<String> lines = new ArrayList<>();
        for (Document account : closed) {
            if (!(account.get("device_token_hashes") instanceof List<?> hashes) || hashes.isEmpty()) continue;
            for (var holder : players.find(Filters.and(Filters.not(CLOSED), Filters.in("device_token_hashes", hashes)))) {
                long shared = holder.getList("device_token_hashes", Object.class).stream().filter(hashes::contains).count();
                lines.add(label(holder) + ": " + shared + " from " + label(account));
            }
        }
        return lines;
    }

    private static boolean filled(@Nullable Object value) {
        return value instanceof String text && !text.isEmpty();
    }

    private static String label(Document player) {
        return "#" + player.get("pid") + " (" + player.get("uuid") + ")";
    }
}
