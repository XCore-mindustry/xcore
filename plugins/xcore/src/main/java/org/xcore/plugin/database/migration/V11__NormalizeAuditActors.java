package org.xcore.plugin.database.migration;

import arc.util.Log;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import jakarta.inject.Singleton;

/**
 * Migration v11: gives plugin-written audit rows the field names the bot uses. The plugin stored
 * the actor id as {@code actor._id} (the POJO codec's default for a property named "id") and the
 * bot stored the dedupe key as {@code integrity.dedupeKey}; both sides now write {@code actor.id}
 * and {@code integrity.dedupe_key}. Rows that already have the new name are left alone.
 */
@Singleton
public class V11__NormalizeAuditActors implements Migration {
    static final String COLLECTION = "moderation_audit";

    @Override
    public int getVersion() {
        return 11;
    }

    @Override
    public String getDescription() {
        return "Rename audit actor._id to actor.id and integrity.dedupeKey to integrity.dedupe_key";
    }

    @Override
    public void up(MongoDatabase database) {
        var audit = database.getCollection(COLLECTION);
        long actors = audit.updateMany(
                Filters.and(Filters.exists("actor._id"), Filters.exists("actor.id", false)),
                Updates.rename("actor._id", "actor.id")).getModifiedCount();
        long keys = audit.updateMany(
                Filters.and(Filters.exists("integrity.dedupeKey"), Filters.exists("integrity.dedupe_key", false)),
                Updates.rename("integrity.dedupeKey", "integrity.dedupe_key")).getModifiedCount();
        Log.info("[Migrations v11] Renamed the actor id in @ audit rows and the dedupe key in @.", actors, keys);
    }
}
