import server.HttpMethod;
import server.HttpRequest;
import server.HttpResponse;
import server.StaticFileHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
public class TraversalTest {
    private static HttpRequest request(String target) {
        return new HttpRequest(
                HttpMethod.GET,
                "GET",
                target,
                "HTTP/1.1",
                Collections.emptyMap()
        );
    }
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("traversal-test-");
        Path documentRoot = Files.createDirectory(
                sandbox.resolve("www")
        );
        try {
            Path publicFile = documentRoot.resolve("index.html");
            Files.writeString(
                    publicFile,
                    "<h1>Public file</h1>"
            );
            Path secretFile = sandbox.resolve("secret.txt");
            Files.writeString(
                    secretFile,
                    "THIS MUST NEVER BE SERVED"
            );
            StaticFileHandler handler = new StaticFileHandler(documentRoot.toString());
            HttpResponse normal =
                    handler.handle(request("/index.html"));
            check(
                    normal.statusCode() == 200,
                    "Normal file should return 200"
            );
            System.out.println(
                    "PASS: normal file request accepted"
            );
            HttpResponse traversal = handler.handle(request("/../secret.txt"));
            check(
                    traversal.statusCode() == 403,
                    "../ traversal was not blocked"
            );
            System.out.println(
                    "PASS: ../ traversal blocked"
            );
            HttpResponse encodedTraversal = handler.handle(request("/%2e%2e/secret.txt"));
            check(
                    encodedTraversal.statusCode() == 403,
                    "Percent encoded traversal was not blocked"
            );
            System.out.println(
                    "PASS: encoded ../ traversal blocked"
            );
            Files.createDirectory(
                    documentRoot.resolve("images")
            );
            HttpResponse nestedTraversal =
                    handler.handle(
                            request("/images/../../secret.txt")
                    );
            check(
                    nestedTraversal.statusCode() == 403,
                    "Nested traversal was not blocked"
            );
            System.out.println(
                    "PASS: nested traversal blocked"
            );
            System.out.println();
            System.out.println(
                    "All directory traversal tests passed."
            );
        } finally {
            try (var paths = Files.walk(sandbox)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }
}