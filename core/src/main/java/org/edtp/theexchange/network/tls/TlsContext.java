package org.edtp.theexchange.network.tls;

import javax.net.ssl.*;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * TLS context with split trust model:
 * - Server side: uses self-signed certificate from keystore
 * - Client side: permissive handshake, with peer identity pinned after handshake
 */
public class TlsContext {

    private final SSLContext serverContext;
    private final SSLContext clientContext;

    private TlsContext(SSLContext serverContext, SSLContext clientContext) {
        this.serverContext = serverContext;
        this.clientContext = clientContext;
    }

    public static TlsContext create(Path keystorePath, String cn, char[] keystorePassword) {
        try {
            SSLContext serverCtx = SelfSignedCert.createSSLContext(keystorePath, cn, keystorePassword);
            SSLContext clientCtx = createPermissiveClientContext();
            return new TlsContext(serverCtx, clientCtx);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create TLS context", e);
        }
    }

    public SSLServerSocketFactory getServerSocketFactory() {
        return serverContext.getServerSocketFactory();
    }

    public SSLSocketFactory getSocketFactory() {
        return clientContext.getSocketFactory();
    }

    private static SSLContext createPermissiveClientContext()
            throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAll = new TrustManager[] {
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, trustAll, null);
        return ctx;
    }

    public static void configureSocket(SSLSocket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.setEnabledCipherSuites(new String[]{
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256"
        });
    }
}
