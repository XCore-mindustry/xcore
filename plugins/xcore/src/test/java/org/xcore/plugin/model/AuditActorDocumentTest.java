package org.xcore.plugin.model;

import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bot writes and queries {@code actor.id}; the plugin must use the same field, not the
 * {@code _id} the POJO codec gives a property named "id" by default.
 */
class AuditActorDocumentTest {
    private static final CodecRegistry CODECS = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));

    @Test
    void storesTheActorIdAsId() {
        var actor = AuditActor.builder().type(AuditActorType.PLAYER_ADMIN).id("123").nameSnapshot("admin").build();
        var document = new BsonDocument();
        CODECS.get(AuditActor.class).encode(new BsonDocumentWriter(document), actor, EncoderContext.builder().build());

        assertThat(document.getString("id").getValue()).isEqualTo("123");
        assertThat(document.containsKey("_id")).isFalse();
    }

    @Test
    void readsTheActorIdTheBotWrites() {
        var document = BsonDocument.parse("{type: 'DISCORD_USER', id: '530', name_snapshot: 'ospx', discord_id: '530'}");
        var actor = CODECS.get(AuditActor.class).decode(new BsonDocumentReader(document), DecoderContext.builder().build());

        assertThat(actor.id).isEqualTo("530");
    }
}
