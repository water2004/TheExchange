package org.edtp.theexchange.network.tls;

import javax.net.ssl.*;
import java.nio.file.Path;

/**
 * TLS context factory providing SSLServerSocketFactory and SSLSocketFactory.
 * Uses self-signed certificates with PKCS12 keystore.
 */
public class TlsContext {

    private final SSLContext sslContext;

    public TlsContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    public static TlsContext create(Path keystorePath, String cn, char[] keystorePassword) {
        try {
            SSLContext ctx = SelfSignedCert.createSSLContext(keystorePath, cn, keystorePassword);
            return new TlsContext(ctx);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create TLS context", e);
        }
    }

    public SSLServerSocketFactory getServerSocketFactory() {
        return sslContext.getServerSocketFactory();
    }

    public SSLSocketFactory getSocketFactory() {
        return sslContext.getSocketFactory();
    }

    /**
     * Configure a socket for TLS 1.3 only, with strong cipher suites.
     */
    public static void configureSocket(SSLSocket socket) {
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.setEnabledCipherSuites(new String[]{
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256"
        });
    }
}
