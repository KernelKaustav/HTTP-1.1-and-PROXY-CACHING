package proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
public final class CachePolicy {
    private boolean noStore;
    private boolean noCache;
    private boolean privateResponse;
    private long maxAgeSeconds = -1;
    private long sharedMaxAgeSeconds = -1;
    private CachePolicy() {
    }
    public static CachePolicy parse(String header) {
        CachePolicy policy = new CachePolicy();
        if (header == null || header.trim().isEmpty()) {
            return policy;
        }
        List<String> directives = splitDirectives(header);
        if (directives == null) {
            // Malformed quoting: avoid storing or reusing the response.
            policy.noStore = true;
            policy.noCache = true;
            return policy;
        }
        boolean seenMaxAge = false;
        boolean seenSharedMaxAge = false;
        for (String directive : directives) {
            int equals = directive.indexOf('=');
            String name = (
                    equals < 0
                            ? directive
                            : directive.substring(0, equals)
            ).trim().toLowerCase(Locale.ROOT);
            String value = equals < 0
                    ? null
                    : directive.substring(equals + 1).trim();
            switch (name) {
                case "no-store":
                    policy.noStore = true;
                    break;
                case "no-cache":
                    policy.noCache = true;
                    break;
                case "private":
                    policy.privateResponse = true;
                    break;
                case "max-age":
                    if (seenMaxAge) {
                        policy.noCache = true;
                    }
                    seenMaxAge = true;
                    policy.maxAgeSeconds = parseSeconds(value);

                    if (policy.maxAgeSeconds < 0) {
                        policy.noCache = true;
                    }
                    break;
                case "s-maxage":
                    if (seenSharedMaxAge) {
                        policy.noCache = true;
                    }
                    seenSharedMaxAge = true;
                    policy.sharedMaxAgeSeconds = parseSeconds(value);
                    if (policy.sharedMaxAgeSeconds < 0) {
                        policy.noCache = true;
                    }
                    break;
                default:
                    // Ignore unsupported extension directives.
                    break;
            }
        }
        return policy;
    }
    public boolean noStore() {
        return noStore;
    }
    public boolean requiresValidation() {
        return noCache;
    }
    public boolean allowsSharedStorage() {
        return !noStore && !privateResponse;
    }
    public long maxAgeSeconds() {
        return maxAgeSeconds;
    }
    public long freshnessLifetimeMillis() {
        long seconds = sharedMaxAgeSeconds >= 0
                ? sharedMaxAgeSeconds
                : maxAgeSeconds;
        return seconds < 0 ? 0 : seconds * 1000;
    }
    public boolean isFresh(long ageMillis) {
        return allowsSharedStorage()
                && !requiresValidation()
                && ageMillis >= 0
                && ageMillis < freshnessLifetimeMillis();
    }
    private static long parseSeconds(String value) {
        if (value == null) {
            return -1;
        }
        if (value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("[0-9]+")) {
            return -1;
        }
        long result = 0;
        long limit = Long.MAX_VALUE / 1000;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';

            // Prevent overflow when converting seconds to milliseconds.
            if (result > (limit - digit) / 10) {
                return limit;
            }
            result = result * 10 + digit;
        }
        return result;
    }
    private static List<String> splitDirectives(String header) {
        List<String> result = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (quoted && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                result.add(header.substring(start, i));
                start = i + 1;
            }
        }
        if (quoted || escaped) {
            return null;
        }
        result.add(header.substring(start));
        return result;
    }
}