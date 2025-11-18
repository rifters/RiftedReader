import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AppLogger {
    private static final String DEFAULT_TAG = "AppLogger";
    private static File logFile;
    private static String sessionId;
    private static JSONObject sessionMetadata;

    // Counters
    private static int taskCount = 0;
    private static int eventCount = 0;
    private static int userActionCount = 0;
    private static int performanceCount = 0;
    private static long totalPerformanceTime = 0;

    // Tag breakdown
    private static Map<String, Integer> tagCounts = new HashMap<>();

    public static void init(Context context, String fileName) {
        if (BuildConfig.DEBUG) {
            File dir = context.getExternalFilesDir("logs");
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            logFile = new File(dir, fileName);
        }
    }

    public static void startSession(Context context, String epubFileName) {
        if (BuildConfig.DEBUG) {
            sessionId = UUID.randomUUID().toString();
            sessionMetadata = new JSONObject();
            try {
                sessionMetadata.put("sessionId", sessionId);
                sessionMetadata.put("epubFile", epubFileName);
                sessionMetadata.put("appVersion", BuildConfig.VERSION_NAME);
                sessionMetadata.put("deviceModel", Build.MODEL);
                sessionMetadata.put("androidVersion", Build.VERSION.RELEASE);
                sessionMetadata.put("timestamp", System.currentTimeMillis());
            } catch (Exception e) {
                Log.e(DEFAULT_TAG, "Failed to set session metadata", e);
            }
            logEvent("SessionStart", "New session started with metadata");
        }
    }

    public static void endSession() {
        if (BuildConfig.DEBUG && sessionId != null) {
            try {
                JSONObject summary = new JSONObject();
                summary.put("sessionId", sessionId);
                summary.put("tasks", taskCount);
                summary.put("events", eventCount);
                summary.put("userActions", userActionCount);
                summary.put("performanceLogs", performanceCount);
                summary.put("avgPerformanceTimeMs", performanceCount > 0 ? totalPerformanceTime / performanceCount : 0);
                summary.put("tagBreakdown", new JSONObject(tagCounts));

                logEvent("SessionSummary", summary.toString());
            } catch (Exception e) {
                Log.e(DEFAULT_TAG, "Failed to create session summary", e);
            }

            logEvent("SessionEnd", "Session ended");
            sessionId = null;
            sessionMetadata = null;

            // Reset counters
            taskCount = eventCount = userActionCount = performanceCount = 0;
            totalPerformanceTime = 0;
            tagCounts.clear();
        }
    }

    private static void writeToFile(String entry) {
        if (logFile == null) return;
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(entry + "\n");
        } catch (IOException e) {
            Log.e(DEFAULT_TAG, "Failed to write log file", e);
        }
    }

    // Custom log levels
    public static void task(String tag, String message, String contextTag) {
        taskCount++;
        incrementTag(contextTag);
        logCustom("TASK", tag, message, contextTag);
    }

    public static void event(String tag, String message, String contextTag) {
        eventCount++;
        incrementTag(contextTag);
        logCustom("EVENT", tag, message, contextTag);
    }

    public static void userAction(String tag, String message, String contextTag) {
        userActionCount++;
        incrementTag(contextTag);
        logCustom("USER_ACTION", tag, message, contextTag);
    }

    public static void performance(String tag, String message, long durationMs, String contextTag) {
        performanceCount++;
        totalPerformanceTime += durationMs;
        incrementTag(contextTag);
        logCustom("PERFORMANCE", tag, message + " | Duration: " + durationMs + "ms", contextTag);
    }

    private static void logCustom(String level, String tag, String message, String contextTag) {
        if (BuildConfig.DEBUG) {
            String entry = level + "/" + (tag != null ? tag : DEFAULT_TAG) + ": " + message + " [tag=" + contextTag + "]";
            Log.d(DEFAULT_TAG, entry);
            writeToFile(entry);
        }
    }

    private static void incrementTag(String contextTag) {
        if (contextTag == null || contextTag.isEmpty()) return;
        tagCounts.put(contextTag, tagCounts.getOrDefault(contextTag, 0) + 1);

        // Hierarchical support
        if (contextTag.contains("/")) {
            String[] parts = contextTag.split("/");
            StringBuilder hierarchy = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) hierarchy.append("/");
                hierarchy.append(parts[i]);
                String partial = hierarchy.toString();
                tagCounts.put(partial, tagCounts.getOrDefault(partial, 0) + 1);
            }
        }
    }

    public static void logEvent(String eventName, String details) {
        if (BuildConfig.DEBUG) {
            try {
                JSONObject event = new JSONObject();
                event.put("sessionId", sessionId != null ? sessionId : "no-session");
                event.put("event", eventName);
                event.put("details", details);
                event.put("timestamp", System.currentTimeMillis());

                if (sessionMetadata != null) {
                    event.put("metadata", sessionMetadata);
                }

                String jsonLog = event.toString();
                Log.d(DEFAULT_TAG, "EVENT: " + jsonLog);
                writeToFile(jsonLog);
            } catch (Exception e) {
                Log.e(DEFAULT_TAG, "Failed to log event", e);
            }
        }
    }
}