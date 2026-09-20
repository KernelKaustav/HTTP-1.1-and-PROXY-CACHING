package server;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;

public class StaticFileHandler implements RequestHandler{
    private final Path documentRoot;
    private static final String INDEX_FILE = "index.html";
    public StaticFileHandler(String documentRoot){
        Path root = Paths.get(documentRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "Document root does not exist or is not a directory: " + root);
        }
        this.documentRoot = root;
    }
    public void handle(Socket clientSocket) throws IOException {
        try(InputStream rawin = clientSocket.getInputStream();
            OutputStream rawout = clientSocket.getOutputStream()){
                BufferedReader reader = new BufferedReader(new InputStreamReader(rawin , "US-ASCII"));
                String reqline = reader.readLine();
                if (reqline == null || reqline.isEmpty()) {
                sendError(rawout, 400, "Bad Request");
                return;
                }
                String[] parts = reqline.split("\\s+");
                if (parts.length < 2){
                    sendError(rawout,400,"Bad Request");
                    return;
                }
                String method = parts[0].toUpperCase();
                String uri    = parts[1];
                String headerline;
                while ((headerline  = reader.readLine()) !=null && !headerline.isEmpty()){

                }
                if (!method.equals("GET") && !method.equals("HEAD")) {
                sendError(rawout, 405, "Method Not Allowed");
                return;
                }
                String cleanUri = stripQuery(uri);
                cleanUri = decodePercent(cleanUri);
                Path resolved = resolveAndValidate(cleanUri);
                if (resolved == null) {
                sendError(rawout, 403, "Forbidden");
                return;
                }
                if (Files.isDirectory(resolved)) {
                resolved = resolved.resolve(INDEX_FILE);
                }
                if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                sendError(rawout, 404, "Not Found");
                return;
                }
                if (!Files.isReadable(resolved)) {
                sendError(rawout, 403, "Forbidden");
                return;
                }
                String mimeType = MimeTypes.getMimeType(resolved.getFileName().toString());
                long fileSize   = Files.size(resolved);
                StringBuilder resp = new StringBuilder();
                resp.append("HTTP/1.1 200 OK\r\n");
                resp.append("Content-Type: ").append(mimeType).append("\r\n");
                resp.append("Content-Length: ").append(fileSize).append("\r\n");
                resp.append("Connection: close\r\n");
                resp.append("\r\n");
                rawout.write(resp.toString().getBytes("US-ASCII"));
                if (!method.equals("HEAD")) {
                try (InputStream fileIn = new BufferedInputStream(
                        Files.newInputStream(resolved))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fileIn.read(buf)) != -1) {
                        rawout.write(buf, 0, n);
                        }
                    }
                }
                rawout.flush();
            }
    }
    private Path resolveAndValidate(String uri){
        if (uri.startsWith("/")){
            uri = uri.substring(1);
        }
        if(uri.isEmpty()){
            uri = ".";
        }
        Path resolved = documentRoot.resolve(uri).normalize();
        if(!resolved.startsWith(documentRoot)){
            return null;
        }
        return resolved;
    }
    private static String stripQuery(String uri){
        int q = uri.indexOf('?');
        if (q != -1) uri = uri.substring(0, q);
        int h = uri.indexOf('#');
        if (h != -1) uri = uri.substring(0, h);
        return uri;
    }
    private static String decodePercent(String s){
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
             if (c == '%' && i + 2 < s.length()){
                try{
                    int code = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    sb.append((char) code);
                    i += 2;
                    continue;
                }catch(NumberFormatException ignored){}
            }
            sb.append(c); 
        }
        return sb.toString();
    }
    private static void sendError(OutputStream out, int code, String reason) throws IOException{
        String body = "<html><body><h1>" + code + " " + reason + "</h1></body></html>\n";
        byte[] bodyBytes = body.getBytes("UTF-8");
        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        resp.append("Content-Type: text/html; charset=UTF-8\r\n");
        resp.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        resp.append("Connection: close\r\n");
        resp.append("\r\n");
        out.write(resp.toString().getBytes("US-ASCII"));
        out.write(bodyBytes);
        out.flush();
    }
}