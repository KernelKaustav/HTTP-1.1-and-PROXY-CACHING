package server;
import java.util.*;
public final class MimeTypes{
    private static final String DEFAULT_MIME = "application/octet-stream";
    private static final Map<String, String> EXT_MAP;
    static{
        Map<String, String> m = new HashMap<>();
        m.put("html", "text/html; charset=UTF-8");
        m.put("htm",  "text/html; charset=UTF-8");
        m.put("css",  "text/css; charset=UTF-8");
        m.put("js",   "application/javascript; charset=UTF-8");
        m.put("json", "application/json; charset=UTF-8");
        m.put("xml",  "application/xml; charset=UTF-8");
        m.put("txt",  "text/plain; charset=UTF-8");
        m.put("csv",  "text/csv; charset=UTF-8");
        m.put("png",  "image/png");
        m.put("jpg",  "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("gif",  "image/gif");
        m.put("svg",  "image/svg+xml");
        m.put("ico",  "image/x-icon");
        m.put("webp", "image/webp");
        m.put("woff",  "font/woff");
        m.put("woff2", "font/woff2");
        m.put("ttf",   "font/ttf");
        m.put("otf",   "font/otf");
        m.put("pdf",  "application/pdf");
        m.put("zip",  "application/zip");
        m.put("gz",   "application/gzip");
        m.put("tar",  "application/x-tar");
        m.put("mp3",  "audio/mpeg");
        m.put("mp4",  "video/mp4");
        m.put("webm", "video/webm");
        EXT_MAP = Collections.unmodifiableMap(m);
    }
    private MimeTypes() { }
    public static String getMimeType(String fileName){
        if (fileName==null || fileName.isEmpty()){
            return DEFAULT_MIME;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot == -1 || dot == fileName.length() - 1) {
            return DEFAULT_MIME;
        }
        String ext = fileName.substring(dot + 1).toLowerCase();
        return EXT_MAP.getOrDefault(ext, DEFAULT_MIME);
    }
    public static Map<String, String> allMappings() {
        return EXT_MAP;
    }
}