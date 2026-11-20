package org.xcore.plugin.database.migration;

import arc.util.Log;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Migration v13: empties the grants that account merges before 5.11.0 left in
 * {@code permission_grants} for a closed account (uuid {@code merged:<old uuid>}), as a merge
 * now does (protocol/storage/merges/README.md). They sat on the old uuid, where whoever connects
 * with it next would get them and set the password with the first {@code /login}, or on the
 * {@code merged:} uuid itself, which the bot's role sync kept while the closed account still had
 * a Discord link.
 * <p>
 * Only documents that still hold grants are written, so a second run changes nothing.
 */
@Singleton
public class V13__RevokeClosedAccountGrants implements Migration {
    static final String PLAYERS = "players";
    static final String GRANTS = "permission_grants";
    static final String MERGED_PREFIX = "merged:";

    @Override
    public int getVersion() {
        return 13;
    }

    @Override
    public String getDescription() {
        return "Empty the staff grants account merges left for closed accounts";
    }

    @Override
    public void up(MongoDatabase database) {
        List<String> revoked = apply(database);
        Log.info("[Migrations v13] Emptied the grants of @ uuids of closed accounts.", revoked.size());
        revoked.forEach(line -> Log.warn("[Migrations v13] Grants emptied: @", line));
    }

    /** One line per uuid whose grants were emptied: the closed account, the grants, who holds the uuid now. */
    List<String> apply(MongoDatabase database) {
        MongoCollection<Document> players = database.getCollection(PLAYERS);
        MongoCollection<Document> grants = database.getCollection(GRANTS);
        List<String> revoked = new ArrayList<>();
        var closed = players.find(Filters.regex("uuid", "^" + Pattern.quote(MERGED_PREFIX)))
                .projection(Projections.include("uuid", "pid"));
        for (Document player : closed) {
            String closedUuid = player.getString("uuid");
            String oldUuid = closedUuid.substring(MERGED_PREFIX.length());
            for (String uuid : List.of(oldUuid, closedUuid)) {
                Bson held = Filters.and(Filters.eq("_id", uuid), Filters.exists("grants.0"));
                Document document = grants.find(held).first();
                if (document == null) {
                    continue;
                }
                if (grants.updateOne(held, new Document("$set", new Document("grants", List.of()))
                        .append("$inc", new Document("revision", 1L))).getModifiedCount() == 0) {
                    continue;
                }
                Document holder = uuid.equals(oldUuid) ? players.find(Filters.eq("uuid", oldUuid)).first() : null;
                revoked.add(uuid + " of closed #" + player.get("pid") + ": " + describe(document)
                        + (holder != null ? ", uuid now held by #" + holder.get("pid") : ""));
            }
        }
        return revoked;
    }

    private static String describe(Document document) {
        List<String> names = new ArrayList<>();
        for (Document grant : document.getList("grants", Document.class, List.of())) {
            String name = grant.getString("role") != null ? grant.getString("role") : grant.getString("node");
            String source = grant.getString("source");
            names.add(name + (source != null ? " (" + source + ")" : ""));
        }
        return String.join(", ", names);
    }
}
