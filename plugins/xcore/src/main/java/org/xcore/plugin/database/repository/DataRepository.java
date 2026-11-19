package org.xcore.plugin.database.repository;

import arc.util.Log;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.xcore.plugin.config.TomlSecretsConfig;
import org.xcore.plugin.database.MongoAsync;
import org.xcore.plugin.database.ReactiveMongoStore;
import org.xcore.plugin.model.ModelData;

import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.eq;

public abstract class DataRepository<T extends ModelData> {
    protected final MongoDatabase database;
    protected final MongoCollection<T> collection;
    protected final com.mongodb.reactivestreams.client.MongoCollection<T> reactiveCollection;
    protected final TomlSecretsConfig secretsConfig;

    protected DataRepository(MongoDatabase database,
                             ReactiveMongoStore reactiveMongoStore,
                             String collectionName,
                             Class<T> clazz,
                             TomlSecretsConfig secretsConfig) {
        this.database = database;
        this.collection = database.getCollection(collectionName, clazz);
        this.reactiveCollection = reactiveMongoStore != null ? reactiveMongoStore.collection(collectionName, clazz) : null;
        this.secretsConfig = secretsConfig;

        collection.createIndex(new Document("version", -1));
        collection.createIndex(new Document("is_visible", -1));
    }

    protected DataRepository(MongoDatabase database, String collectionName, Class<T> clazz, TomlSecretsConfig secretsConfig) {
        this(database, null, collectionName, clazz, secretsConfig);
    }

    public boolean save(T data) {
        return save(null, data);
    }

    public boolean save(com.mongodb.client.ClientSession session, T data) {
        if (data == null) return false;

        if (isReadOnly()) {
            Log.warn("[XCore-DB] Database is in Read-Only mode. Save ignored for @", data.getClass().getSimpleName());
            return false;
        }

        if (data.createdModelTime == 0) {
            data.createdModelTime = System.currentTimeMillis();
        }

        data.editModelTime = System.currentTimeMillis();

        if (data.id == null) {
            if (session != null) {
                collection.insertOne(session, data);
            } else {
                collection.insertOne(data);
            }
        } else {
            if (session != null) {
                collection.replaceOne(session, eq("_id", data.id), data, new ReplaceOptions().upsert(true));
            } else {
                collection.replaceOne(eq("_id", data.id), data, new ReplaceOptions().upsert(true));
            }
        }
        return true;
    }

    public CompletionStage<Boolean> saveAsync(T data) {
        if (data == null) return CompletableFuture.completedFuture(false);

        if (isReadOnly()) {
            Log.warn("[XCore-DB] Database is in Read-Only mode. Save ignored for @", data.getClass().getSimpleName());
            return CompletableFuture.completedFuture(false);
        }

        if (data.createdModelTime == 0) {
            data.createdModelTime = System.currentTimeMillis();
        }

        data.editModelTime = System.currentTimeMillis();

        if (reactiveCollection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Reactive MongoDB store is required for saveAsync"));
        }

        if (data.id == null) {
            return MongoAsync.first(reactiveCollection.insertOne(data))
                    .thenApply(result -> true);
        } else {
            return MongoAsync.first(reactiveCollection.replaceOne(eq("_id", data.id), data, new ReplaceOptions().upsert(true)))
                    .thenApply(result -> true);
        }
    }

    public T findById(ObjectId id) {
        return findById(null, id);
    }

    public T findById(com.mongodb.client.ClientSession session, ObjectId id) {
        if (id == null) return null;
        if (session != null) {
            return collection.find(session, eq("_id", id)).first();
        }
        return collection.find(eq("_id", id)).first();
    }

    public CompletionStage<T> findByIdAsync(ObjectId id) {
        if (id == null) return CompletableFuture.completedFuture(null);
        if (reactiveCollection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Reactive MongoDB store is required for findByIdAsync"));
        }
        return MongoAsync.first(reactiveCollection.find(eq("_id", id)));
    }

    /**
     * Copies the ban or mute of an account a merge closes to the account that survives, as
     * protocol/storage/merges/README.md defines it: only an active one, only if the surviving account
     * has no active one of its own, and in place of its expired one. The copy keeps every field but
     * uuid, pid, name and reason, so it carries what the plugin's model does not know about too.
     * Returns the source's document when it was copied, null otherwise.
     */
    protected @Nullable Document transferPunishment(@Nullable ClientSession session, PunishmentTransfer transfer,
                                                    Consumer<Document> finish) {
        if (isReadOnly()) throw new IllegalStateException("Cannot transfer punishments in Read-Only mode");
        var raw = collection.withDocumentClass(Document.class);
        var source = first(raw, session, eq("uuid", transfer.sourceUuid()));
        if (source == null || !active(source)) return null;
        var target = first(raw, session, eq("uuid", transfer.targetUuid()));
        if (target != null && active(target)) return null;

        var copy = new Document(source);
        copy.remove("_id");
        copy.put("uuid", transfer.targetUuid());
        if (transfer.survivingPid() == null) copy.remove("pid");
        else copy.put("pid", transfer.survivingPid());
        copy.put("name", transfer.targetName());
        copy.put("reason", "[Merged from #" + transfer.sourcePid() + "] " + Objects.toString(source.get("reason"), ""));
        finish.accept(copy);
        // One per uuid: the copy takes the place of the expired one, which a lookup could otherwise find first.
        if (target != null) {
            if (session == null) raw.replaceOne(eq("_id", target.get("_id")), copy);
            else raw.replaceOne(session, eq("_id", target.get("_id")), copy);
        } else if (session == null) {
            raw.insertOne(copy);
        } else {
            raw.insertOne(session, copy);
        }
        return source;
    }

    /** No expire_date is a permanent punishment. */
    private static boolean active(Document punishment) {
        return !(punishment.get("expire_date") instanceof Date expiry) || expiry.toInstant().isAfter(Instant.now());
    }

    private static @Nullable Document first(MongoCollection<Document> raw, @Nullable ClientSession session, Bson filter) {
        return session == null ? raw.find(filter).first() : raw.find(session, filter).first();
    }

    public boolean isReadOnly() {
        return secretsConfig != null && secretsConfig.database != null && secretsConfig.database.readOnly;
    }

    public long count() {
        return collection.countDocuments();
    }

    public CompletionStage<Long> countAsync() {
        if (reactiveCollection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Reactive MongoDB store is required for countAsync"));
        }
        return MongoAsync.first(reactiveCollection.countDocuments());
    }

    public long count(Bson filter) {
        return collection.countDocuments(filter);
    }

    public CompletionStage<Long> countAsync(Bson filter) {
        if (reactiveCollection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Reactive MongoDB store is required for countAsync"));
        }
        return MongoAsync.first(reactiveCollection.countDocuments(filter));
    }
}
