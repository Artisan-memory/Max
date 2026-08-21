package tw.nekomimi.nekogram.sync;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import xyz.nextalone.nagram.NaConfig;

/** Thin HTTP layer over the max-sync server. Calls block, so keep them off the main thread. */
public class SyncApi {
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    public static class SyncException extends IOException {
        public SyncException(String message) {
            super(message);
        }
    }

    private SyncApi() {
    }

    public static String baseUrl() {
        String url = NaConfig.INSTANCE.getSyncServerUrl().String().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(baseUrl()) && SyncSecrets.isSet(SyncSecrets.KEY_ACCESS_PASSWORD);
    }

    /** Exchanges the access password for a token. Also serves as the connection test. */
    public static String login() throws IOException {
        String base = baseUrl();
        if (TextUtils.isEmpty(base)) {
            throw new SyncException("server address is empty");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("password", SyncSecrets.get(SyncSecrets.KEY_ACCESS_PASSWORD));
        } catch (Exception e) {
            throw new SyncException("cannot build request");
        }

        HttpURLConnection connection = open(base + "/v1/auth/login", "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new SyncException("access password rejected");
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new SyncException("not found — check the address and the proxy token");
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new SyncException("server returned " + code);
            }
            try {
                String token = new JSONObject(read(connection.getInputStream())).optString("token");
                if (TextUtils.isEmpty(token)) {
                    throw new SyncException("server returned no token");
                }
                return token;
            } catch (SyncException e) {
                throw e;
            } catch (Exception e) {
                throw new SyncException("malformed response");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setUseCaches(false);
        String proxyToken = SyncSecrets.get(SyncSecrets.KEY_PROXY_TOKEN);
        if (!TextUtils.isEmpty(proxyToken)) {
            connection.setRequestProperty("X-Proxy-Token", proxyToken);
        }
        String fingerprint = NaConfig.INSTANCE.getSyncCertFingerprint().String().trim();
        if (!TextUtils.isEmpty(fingerprint) && connection instanceof HttpsURLConnection) {
            CertificatePinner.apply((HttpsURLConnection) connection, fingerprint);
        }
        return connection;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }
}
