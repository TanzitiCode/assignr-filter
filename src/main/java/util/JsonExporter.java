package util;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import model.Assignment;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes assignments to a JSON file so other tools (like the Python RAG assistant in rag/)
 * can read them.
 *
 * WHY JSON?
 * It's the "bridge" between the Java scraper and the Python side. Python doesn't care
 * what language wrote the file — it just needs the field names to stay the same.
 * If you rename a field in Assignment.java, update rag/assignments.py too.
 */
public class JsonExporter {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        // LocalDateTime has no built-in Gson support — write it as ISO text like "2026-10-18T09:30"
        .registerTypeAdapter(LocalDateTime.class,
            (JsonSerializer<LocalDateTime>) (src, type, ctx) -> new JsonPrimitive(src.toString()))
        // venuePopupId is internal scraper bookkeeping, not useful outside Java
        .setExclusionStrategies(new ExclusionStrategy() {
            @Override public boolean shouldSkipField(FieldAttributes f) { return f.getName().equals("venuePopupId"); }
            @Override public boolean shouldSkipClass(Class<?> c) { return false; }
        })
        .create();

    public static void export(List<Assignment> assignments, String filePath) throws IOException {
        try (Writer out = Files.newBufferedWriter(Path.of(filePath), StandardCharsets.UTF_8)) {
            GSON.toJson(assignments, out);
        }
    }
}
