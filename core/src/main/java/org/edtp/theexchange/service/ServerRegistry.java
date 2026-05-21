package org.edtp.theexchange.service;

import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.NetworkManager;
import org.edtp.theexchange.storage.DatabaseManager;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ServerRegistry {

    private final DatabaseManager db;
    private final NetworkManager networkManager;
    private final ConcurrentHashMap<String, RemoteServer> servers = new ConcurrentHashMap<>();

    public ServerRegistry(DatabaseManager db, NetworkManager networkManager) {
        this.db = db;
        this.networkManager = networkManager;
    }

    public boolean isNetworkAvailable() {
        return networkManager != null;
    }

    public void loadFromDatabase() {
        db.lock();
        String sql = "SELECT name, address, port, password_hash, enabled FROM remote_servers";
        try (Statement stmt = db.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RemoteServer server = new RemoteServer(
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getInt("port"),
                        rs.getString("password_hash"),
                        rs.getInt("enabled") != 0
                );
                servers.put(server.getName(), server);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load servers from database", e);
        } finally {
            db.unlock();
        }
    }

    public void connectAllEnabled() {
        if (networkManager == null) return;
        for (RemoteServer server : getAllServers()) {
            if (server.isEnabled()) {
                networkManager.connectToRemote(server);
            }
        }
    }

    public RemoteServer addServer(String name, String address, int port, String password) {
        RemoteServer existing = servers.get(name);
        if (existing != null && networkManager != null) {
            networkManager.disconnect(name);
        }

        String sql = "INSERT OR REPLACE INTO remote_servers (name, address, port, password_hash, enabled) " +
                "VALUES (?, ?, ?, ?, 1)";
        db.lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, address);
            ps.setInt(3, port);
            ps.setString(4, password);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add server " + name, e);
        } finally {
            db.unlock();
        }

        RemoteServer server = new RemoteServer(name, address, port, password, true);
        servers.put(name, server);

        if (networkManager != null) {
            networkManager.connectToRemote(server);
        }

        return server;
    }

    public boolean removeServer(String name) {
        RemoteServer server = servers.remove(name);
        if (server == null) return false;

        if (networkManager != null) {
            networkManager.disconnect(name);
        }

        String sql = "DELETE FROM remote_servers WHERE name = ?";
        db.lock();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove server " + name, e);
        } finally {
            db.unlock();
        }
        return true;
    }

    public RemoteServer getServer(String name) {
        return servers.get(name);
    }

    public List<RemoteServer> getAllServers() {
        return new ArrayList<>(servers.values());
    }

    public ServerStatus getStatus(String name) {
        if (networkManager == null) return ServerStatus.OFFLINE;
        return networkManager.getStatus(name);
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }
}
