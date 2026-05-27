package org.edtp.theexchange.service;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ServerRegistry {

    private final NetworkManager networkManager;
    private final ConcurrentHashMap<String, RemoteServer> servers = new ConcurrentHashMap<>();

    public ServerRegistry(NetworkManager networkManager, List<ExchangeAPI.RemoteServerConfig> remoteConfigs) {
        this.networkManager = networkManager;
        replaceAll(remoteConfigs);
    }

    public boolean isNetworkAvailable() {
        return networkManager != null;
    }

    public void replaceAll(List<ExchangeAPI.RemoteServerConfig> remoteConfigs) {
        servers.clear();
        if (remoteConfigs == null) {
            return;
        }
        for (ExchangeAPI.RemoteServerConfig remote : remoteConfigs) {
            RemoteServer server = new RemoteServer(remote.getName(), remote.getAddress(),
                    remote.getPort(), remote.getPassword(), true);
            servers.put(server.getName(), server);
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
