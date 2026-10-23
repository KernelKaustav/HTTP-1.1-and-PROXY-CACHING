package proxy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ETagGenerator {
    private ETagGenerator() {
    }

    public static String generate(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("Body must not be null");
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder tag = new StringBuilder(digest.length * 2 + 2);
            tag.append('"');

            for (byte value : digest) {
                tag.append(String.format("%02x", value & 0xff));
            }

            return tag.append('"').toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
