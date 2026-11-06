import java.net.URI;

// Verbatim logic of DebugBridgeConfiguration.baseUrl (main@31b88f5),
// re-expressed in Java against java.net.URI (JDK17).
public class Bridge {
    static boolean isWs(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); } // Kotlin Char.isWhitespace parity
    static String kotlinTrim(String s) {
        int a = 0, b = s.length();
        while (a < b && isWs(s.charAt(a))) a++;
        while (b > a && isWs(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
    static String baseUrl(boolean isDebug, String configuredUrl) {
        if (!isDebug) return null;
        String value = kotlinTrim(configuredUrl);
        if (value.isEmpty() || value.length() > 2048) return null;
        URI endpoint;
        try { endpoint = new URI(value); } catch (Exception e) { return null; }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) ||
            endpoint.getHost() == null || endpoint.getHost().isBlank() ||
            endpoint.getRawUserInfo() != null ||
            endpoint.getRawQuery() != null || endpoint.getRawFragment() != null ||
            !(endpoint.getRawPath() == null || endpoint.getRawPath().isEmpty() || endpoint.getRawPath().equals("/")) ||
            (endpoint.getPort() != -1 && !(endpoint.getPort() >= 1 && endpoint.getPort() <= 65535))
        ) return null;
        String out = endpoint.toASCIIString();
        return out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
    }
    public static void main(String[] args) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            String res = baseUrl(true, line);
            System.out.println(res == null ? "NULL" : res);
        }
    }
}
