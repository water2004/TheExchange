package org.edtp.theexchange.network.tls;

import javax.net.ssl.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * TLS context management using JDK keytool-generated self-signed certificates.
 * Pure public JDK API — no internal sun.* imports, no BouncyCastle dependency.
 */
public final class SelfSignedCert {

    private SelfSignedCert() {}

    public static SSLContext createSSLContext(Path keystorePath, String cn,
                                               char[] keystorePassword) throws Exception {
        KeyStore keyStore;

        if (Files.exists(keystorePath)) {
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(Files.newInputStream(keystorePath), keystorePassword);
        } else {
            Files.createDirectories(keystorePath.getParent());
            generateWithKeytool(keystorePath, cn, keystorePassword);
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(Files.newInputStream(keystorePath), keystorePassword);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static void generateWithKeytool(Path keystorePath, String cn,
                                              char[] password) throws Exception {
        // Use a fixed safe DN — cert identity is not used for auth (bcrypt handles that)
        String passStr = new String(password);

        List<String> args = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        String keytool = javaHome + File.separator + "bin" + File.separator + "keytool";

        args.add(keytool);
        args.add("-genkeypair");
        args.add("-alias"); args.add("theexchange");
        args.add("-keyalg"); args.add("RSA");
        args.add("-keysize"); args.add("2048");
        args.add("-sigalg"); args.add("SHA256withRSA");
        args.add("-validity"); args.add("365");
        args.add("-dname"); args.add("CN=TheExchange, OU=TheExchange, O=TheExchange, L=Unknown, ST=Unknown, C=XX");
        args.add("-storetype"); args.add("PKCS12");
        args.add("-keystore"); args.add(keystorePath.toAbsolutePath().toString());
        args.add("-storepass"); args.add(passStr);
        args.add("-keypass"); args.add(passStr);
        args.add("-noprompt");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("keytool failed with exit code " + exitCode + ": " + output);
        }
    }
}
