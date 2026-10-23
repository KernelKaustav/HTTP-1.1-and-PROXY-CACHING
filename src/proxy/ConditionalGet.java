package proxy;

import server.HttpRequest;
import server.HttpResponse;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConditionalGet {
    private static final List<String> NOT_MODIFIED_HEADERS = Arrays.asList(
            "cache-control",
            "content-location",
            "date",
            "etag",
            "expires",
            "vary",
            "warning"
    );

    private ConditionalGet() {
    }

    public static boolean isNotModified(
            HttpRequest request,
            String etag,
            String lastModified
    ) {
        String ifNoneMatch = request.header("if-none-match");

        if (ifNoneMatch != null) {
            return matchesEntityTag(ifNoneMatch, etag);
        }

        String ifModifiedSince = request.header("if-modified-since");

        if (ifModifiedSince == null || lastModified == null) {
            return false;
        }

        Long requestDate = parseDate(ifModifiedSince);
        Long modifiedDate = parseDate(lastModified);

        return requestDate != null
                && modifiedDate != null
                && modifiedDate <= requestDate;
    }

    public static HttpResponse notModified(
            String etag,
            String lastModified,
            Map<String, List<String>> headers
    ) {
        HttpResponse response = new HttpResponse().status(304);

        for (String name : NOT_MODIFIED_HEADERS) {
            List<String> values = headers.get(name);

            if (values != null) {
                for (String value : values) {
                    response.addHeader(name, value);
                }
            }
        }

        if (etag != null && !response.hasHeader("etag")) {
            response.header("ETag", etag);
        }

        if (lastModified != null && !response.hasHeader("last-modified")) {
            response.header("Last-Modified", lastModified);
        }

        return response.header("Connection", "close");
    }

    private static boolean matchesEntityTag(String condition, String currentTag) {
        if (currentTag == null) {
            return false;
        }

        String currentOpaqueTag = stripWeakPrefix(currentTag.trim());

        for (String candidate : splitTags(condition)) {
            String tag = candidate.trim();

            if (tag.equals("*")) {
                return true;
            }

            if (stripWeakPrefix(tag).equals(currentOpaqueTag)) {
                return true;
            }
        }

        return false;
    }

    private static List<String> splitTags(String header) {
        return Arrays.asList(header.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"));
    }

    private static String stripWeakPrefix(String tag) {
        return tag.regionMatches(true, 0, "W/", 0, 2)
                ? tag.substring(2).trim()
                : tag;
    }

    private static Long parseDate(String value) {
        try {
            return ZonedDateTime.parse(
                    value,
                    DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
            ).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
