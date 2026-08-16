package org.edtp.theexchange.network.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Set;
import java.util.Objects;
import java.util.Properties;

/**
 * TOFU peer key store.
 * First connection pins the peer public key; later connections must match it.
 */
public final class PinnedPeerKeyStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PinnedPeerKeyStore.class);
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
            LOGGER.info("Pinned TLS public key for {}", serverName);
            return;
        }

        if (!storedKey.equals(encodedKey)) {
            throw new SSLHandshakeException("Pinned public key mismatch for " + serverName);
        }
    }

    public synchronized void retainOnly(Set<String> allowedServerNames) throws IOException {
        Objects.requireNonNull(allowedServerNames, "allowedServerNames");
        boolean changed = pins.keySet().removeIf(key -> !allowedServerNames.contains(String.valueOf(key)));
        if (changed) {
            store();
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
        try {
            Files.move(tmp, pinFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, pinFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
