package dev.stratus.verification.storage;

import java.time.Instant;
import java.util.List;

public record VerificationReport(String description, Instant timestamp, boolean success, List<VerificationCheck> checks) {
    public VerificationReport {
        checks = List.copyOf(checks);
    }

    public String toJson() {
        var output = new StringBuilder();
        output.append("{\"description\":\"").append(escape(description))
                .append("\",\"timestamp\":\"").append(timestamp).append("\",\"success\":").append(success).append(",\"checks\":[");
        for (var index = 0; index < checks.size(); index++) {
            if (index > 0) output.append(',');
            var check = checks.get(index);
            output.append("{\"name\":\"").append(escape(check.name()))
                    .append("\",\"passed\":").append(check.passed())
                    .append(",\"detail\":\"").append(escape(check.detail())).append("\"}");
        }
        return output.append("]}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
