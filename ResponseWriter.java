package server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
public final class ResponseWriter {

    private ResponseWriter() {
    }
    public static HttpResponse error(int status, String message) {
        return new HttpResponse()
                .status(status)
                .reasonPhrase(reasonFor(status))
                .header("Content-Type", "text/plain; charset=utf-8")
                .body(message + "\n");
    }
    public static byte[] serialize(HttpResponse response, boolean headOnly) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        response.writeTo(buf);
        byte[] all = buf.toByteArray();
        if (!headOnly && !mustHaveNoBody(response.statusCode())) {
            return all;
        }
        int end = headEnd(all);
        return (end < 0) ? all : Arrays.copyOf(all, end);
    }
    public static void write(OutputStream out, HttpResponse response, boolean headOnly) throws IOException {
        out.write(serialize(response, headOnly));
        out.flush();
    }
    public static boolean mustHaveNoBody(int status) {
        return status < 200 || status == 204 || status == 304;
    }
    public static String reasonFor(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 504: return "Gateway Timeout";
            case 505: return "HTTP Version Not Supported";
            default:  return "Unknown";
        }
    }
    private static int headEnd(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }
}
