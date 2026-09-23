package server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
public class StaticFileHandler implements RequestHandler {

    private final Path documentRoot;
    private static final String INDEX_FILE = "index.html";

    public StaticFileHandler(String documentRoot) {
    Path root = Paths.get(documentRoot).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
        throw new IllegalArgumentException(
                "Document root does not exist or is not a directory: " + root);
    }
    try {
        this.documentRoot = root.toRealPath();
    } catch (IOException e) {
        throw new IllegalArgumentException(
                "Could not canonicalize document root: " + root, e);
    }
}
    public HttpResponse handle(HttpRequest request) throws IOException {
        HttpMethod method = request.method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return errorResponse(405, "Method Not Allowed");
        }
        String cleanUri = stripQuery(request.target());
        cleanUri = decodePercent(cleanUri);
        Path resolved = resolveAndValidate(cleanUri);
        if (resolved == null) {
            return errorResponse(403, "Forbidden");
        }
        if(Files.isDirectory(resolved)){
            String indexUri;
            if(cleanUri.endsWith("/")){
                indexUri = cleanUri + INDEX_FILE;
            }else{
                indexUri = cleanUri + "/" + INDEX_FILE  ;
            }
            resolved =  resolveAndValidate(indexUri);
            if (resolved == null){
                return  errorResponse(403, "FORBIDDEN");
            }
        }

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return errorResponse(404, "Not Found");
        }

        if (!Files.isReadable(resolved)) {
            return errorResponse(403, "Forbidden");
        }

        String mimeType = MimeTypes.getMimeType(resolved.getFileName().toString());
        long fileSize = Files.size(resolved);

        HttpResponse response = new HttpResponse()
                .status(200)
                .header("Content-Type", mimeType)
                .header("Connection", request.isKeepAlive() ? "keep-alive" : "close");

        if (method == HttpMethod.HEAD) {
            response.header("Content-Length", String.valueOf(fileSize));
        } else {
            response.body(readAllBytes(resolved)); // body() sets Content-Length itself
        }
        return response;
    }

    private static byte[] readAllBytes(Path path) throws IOException {
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(path))) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = fileIn.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }
private Path resolveAndValidate(String uri) {
    if (uri.startsWith("/")) {
        uri = uri.substring(1);
    }
    if (uri.isEmpty()) {
        uri = ".";
    }
    final Path candidate;
    try {
        candidate = documentRoot.resolve(uri).normalize();
    } catch (Exception e) {
        return null;
    }
    if (!candidate.startsWith(documentRoot)) {
        return null;
    }

    if (Files.exists(candidate)) {
        try {
            Path realPath = candidate.toRealPath();
            if (!realPath.startsWith(documentRoot)) {
                return null;
            }

            return realPath;

        } catch (IOException e) {
            return null;
        }
    }
    return candidate;
}
private static String stripQuery(String uri) {
    int q = uri.indexOf('?');
    if (q != -1) uri = uri.substring(0, q);
    int h = uri.indexOf('#');
    if (h != -1) uri = uri.substring(0, h);
    return uri;
}
private static String decodePercent(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '%' && i + 2 < s.length()) {
            try {
                int code = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                sb.append((char) code);
                i += 2;
                continue;
            } catch (NumberFormatException ignored) {}
        }
        sb.append(c);
    }
        return sb.toString();
}
private static HttpResponse errorResponse(int code, String reason) {
    String body = "<html><body><h1>" + code + " " + reason + "</h1></body></html>\n";
     return new HttpResponse().status(code).header("Content-Type", "text/html; charset=UTF-8").header("Connection", "close").body(body);
}
}