package com.factorio.xenvironment.components;

import com.factorio.xenvironment.EnvironmentComponent;
import com.factorio.xconnector.XConnectorEpoll;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xserver.XClientConnectionHandler;
import com.factorio.xserver.XClientRequestHandler;
import com.factorio.xserver.XServer;

public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }

    public XServer getXServer() {
        return xServer;
    }
}
