package app.movia.android.domain.provider;

import java.net.URI;
import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

/** URL policy for external provider media, independent of any provider runtime. */
public final class ProviderMediaUrlPolicy {
    private ProviderMediaUrlPolicy() {}
    private static final Pattern BTIH = Pattern.compile(
        "(?:[?&])xt=urn:btih:(?:[a-f0-9]{40}|[a-z2-7]{32})(?:&|$)", Pattern.CASE_INSENSITIVE);

    public static boolean isMediaUrl(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 16384) return false;
        for (int i = 0; i < raw.length(); i++)
            if (raw.charAt(i) <= 32 || raw.charAt(i) == 127) return false;
        if (raw.regionMatches(true, 0, "magnet:?", 0, 8)) return BTIH.matcher(raw).find();
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return false;
            if (uri.getRawUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null || host.indexOf('%') >= 0) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.equals("localhost") || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal")) return false;
            int port = uri.getPort();
            if (port == 0 || port > 65535) return false;
            String literal = host.startsWith("[") && host.endsWith("]") ?
                host.substring(1, host.length() - 1) : host;
            if (literal.indexOf(':') >= 0) {
                InetAddress address = InetAddress.getByName(literal);
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                    address.isMulticastAddress()) return false;
                if (bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc)) return false;
            } else if (literal.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}")) {
                String[] parts = literal.split("\\.");
                if (parts.length != 4) return false;
                int[] octets = new int[4];
                for (int i = 0; i < 4; i++) {
                    if (!parts[i].matches("[0-9]{1,3}") || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
                    octets[i] = Integer.parseInt(parts[i]);
                    if (octets[i] > 255) return false;
                }
                int a=octets[0], b=octets[1];
                if (a==0 || a==10 || a==127 || a>=224 || (a==169 && b==254) ||
                    (a==172 && b>=16 && b<=31) || (a==192 && b==168) ||
                    (a==100 && b>=64 && b<=127)) return false;
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }
}
