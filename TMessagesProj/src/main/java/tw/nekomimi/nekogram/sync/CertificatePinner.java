package tw.nekomimi.nekogram.sync;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

import javax.net.ssl.HttpsURLConnection;

/**
 * Optional pinning of the server certificate. A self-hosted server usually carries a certificate
 * no public CA vouches for, and pinning is what makes that safe rather than merely tolerated.
 */
final class CertificatePinner {

    private CertificatePinner() {
    }

    static void apply(HttpsURLConnection connection, String expected) {
        String wanted = normalize(expected);
        if (wanted.isEmpty()) {
            return;
        }
        connection.setHostnameVerifier((hostname, session) -> {
            try {
                Certificate[] chain = session.getPeerCertificates();
                return chain.length > 0 && wanted.equals(fingerprint(chain[0]));
            } catch (Exception e) {
                FileLog.e("maxsync: certificate check failed", e);
                return false;
            }
        });
    }

    static String fingerprint(Certificate certificate) throws CertificateEncodingException, IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String normalize(String fingerprint) {
        return fingerprint.replace(":", "").replace(" ", "").toLowerCase();
    }
}
