package dev.aeroac.neural.admin.training;

import dev.aeroac.locale.AeroMessages;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Size limits apply to bytes actually read, including a file replaced during a refresh. */
final class JsonFiles {
    private JsonFiles() { }
    static byte[] bytes(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException(AeroMessages.tr("admin.training.json_file_exceeds") + limit + AeroMessages.tr("admin.bytes") + path.getFileName());
            return bytes;
        }
    }
    static JsonObject read(Path path, int limit) throws IOException {
        return JsonParser.parseString(new String(bytes(path, limit), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    static String text(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + AeroMessages.tr("admin.training.must_be_text"));
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }
    static long integer(JsonObject json, String key, long fallback) {
        var value = json.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + AeroMessages.tr("admin.training.must_be_numeric"));
        return value.getAsBigDecimal().longValueExact();
    }
    static double number(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || value.isJsonNull()) return Double.NaN;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + AeroMessages.tr("admin.training.must_be_numeric"));
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException(key + AeroMessages.tr("admin.training.must_be_finite"));
        return number;
    }
    static boolean bool(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || value.isJsonNull()) return false;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + AeroMessages.tr("admin.training.must_be_boolean"));
        return value.getAsBoolean();
    }
}
