package org.xcore.plugin.database.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.MongoClientSettings;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The shared MongoDB contract in protocol/storage: the schemas the bot and the website test
 * against too, and the fixtures of every shape a stored document can have.
 */
final class StorageContract {
    static final Path ROOT = Path.of(System.getProperty("xcore.storage.dir", "../../protocol/storage"));
    /** As the plugin's MongoFactory decodes and encodes its models. */
    static final CodecRegistry CODECS = CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));
    private static final JsonWriterSettings CANONICAL = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private static final Map<String, JsonSchema> VALIDATORS = new HashMap<>();

    private StorageContract() {
    }

    static JsonObject schema(String collection) {
        return schemaAt(ROOT.resolve("schemas/" + collection + ".v1.json"));
    }

    static JsonObject schemaAt(Path path) {
        return JsonParser.parseString(text(path)).getAsJsonObject();
    }

    /** Top-level fields the plugin reads ("x-read-by") or writes ("x-written-by"), through any of {@code files}. */
    static Set<String> pluginFields(String collection, String role, String... files) {
        var fields = new TreeSet<String>();
        for (var field : schema(collection).getAsJsonObject("properties").entrySet()) {
            var roles = field.getValue().getAsJsonObject().getAsJsonObject(role);
            if (roles == null || !roles.has("plugin")) continue;
            for (var path : roles.getAsJsonArray("plugin")) {
                if (files.length == 0 || Stream.of(files).anyMatch(path.getAsString()::endsWith)) fields.add(field.getKey());
            }
        }
        return fields;
    }

    /** Every shape a reader can meet: current and legacy documents, and both sides of each migration and merge case. */
    static List<Path> stored(String collection) {
        var paths = new ArrayList<Path>();
        paths.addAll(files(ROOT.resolve("fixtures/" + collection + "/valid")));
        paths.addAll(files(ROOT.resolve("fixtures/" + collection + "/legacy")));
        for (var root : List.of("migrations", "merges")) {
            for (var dir : dirs(ROOT.resolve(root))) {
                paths.addAll(files(dir.resolve("before/" + collection)));
                paths.addAll(files(dir.resolve("after/" + collection)));
            }
        }
        return paths;
    }

    static List<Path> dirs(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (var dirs = Files.list(dir)) {
            return dirs.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BsonDocument read(Path path) {
        return BsonDocument.parse(text(path));
    }

    static <T> T decode(Class<T> type, BsonDocument document) {
        return CODECS.get(type).decode(new BsonDocumentReader(document), DecoderContext.builder().build());
    }

    static <T> BsonDocument encode(Class<T> type, T value) {
        var document = new BsonDocument();
        CODECS.get(type).encode(new BsonDocumentWriter(document), value, EncoderContext.builder().build());
        return document;
    }

    /** Why the schema rejects the document, checked in canonical Extended JSON so int32 and int64 differ. */
    static List<String> errors(String collection, BsonDocument document) {
        var validator = VALIDATORS.computeIfAbsent(collection, StorageContract::validator);
        return validator.validate(document.toJson(CANONICAL), InputFormat.JSON).stream()
                .map(ValidationMessage::getMessage).toList();
    }

    private static JsonSchema validator(String collection) {
        var schemas = new HashMap<String, String>();
        for (var path : files(ROOT.resolve("schemas"))) {
            var text = text(path);
            schemas.put(JsonParser.parseString(text).getAsJsonObject().get("$id").getAsString(), text);
        }
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaLoaders(loaders -> loaders.schemas(schemas)));
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return factory.getSchema(SchemaLocation.of("urn:xcore:storage:" + collection + ":v1"), config);
    }

    private static String text(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
