package org.edtp.theexchange.network.tls;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;

/**
 * TOFU peer key store.
 * First connection pins the peer public key; later connections must match it.
 */
public final class PinnedPeerKeyStore {

    private final Path pinFile;
    private final Properties pins = new Properties();

    public PinnedPeerKeyStore(Path pinFile) {
        this.pinFile = Objects.requireNonNull(pinFile, "pinFile");
        load();
    }

    public void verifyOrPin(String serverName, SSLSocket socket) throws IOException {
        Certificate[] chain;
        try {
            chain = socket.getSession().getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            throw new SSLHandshakeException("Peer certificate unavailable for " + serverName + ": " + e.getMessage());
        }
        if (chain == null || chain.length == 0) {
            throw new SSLHandshakeException("Peer certificate chain is empty for " + serverName);
        }
        verifyOrPin(serverName, chain[0].getPublicKey().getEncoded());
    }

    synchronized void verifyOrPin(String serverName, byte[] publicKeyEncoded) throws IOException {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(publicKeyEncoded, "publicKeyEncoded");

        String encodedKey = Base64.getEncoder().encodeToString(publicKeyEncoded);
        String storedKey = pins.getProperty(serverName);
        if (storedKey == null) {
            pins.setProperty(serverName, encodedKey);
            store();
            System.out.println("[Exchange|TLS] Pinned public key for " + serverName);
            return;
        }

        if (!storedKey.equals(encodedKey)) {
            throw new SSLHandshakeException("Pinned public key mismatch for " + serverName);
        }
    }

    private void load() {
        if (!Files.exists(pinFile)) {
            return;
        }
        try (InputStream in = Files.newInputStream(pinFile)) {
            pins.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load pinned peer keys from " + pinFile, e);
        }
    }

    private void store() throws IOException {
        Path parent = pinFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = pinFile.resolveSibling(pinFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            pins.store(out, "TheExchange pinned peer keys");
        }
        Files.move(tmp, pinFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
