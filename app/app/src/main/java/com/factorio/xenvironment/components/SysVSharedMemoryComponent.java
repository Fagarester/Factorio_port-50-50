package com.factorio.xenvironment.components;

import com.factorio.sysvshm.SysVSHMConnectionHandler;
import com.factorio.sysvshm.SysVSHMRequestHandler;
import com.factorio.sysvshm.SysVSharedMemory;
import com.factorio.xconnector.UnixSocketConfig;
import com.factorio.xconnector.XConnectorEpoll;
import com.factorio.xenvironment.EnvironmentComponent;
import com.factorio.xserver.SHMSegmentManager;
import com.factorio.xserver.XServer;

public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    private final XServer xServer;

    public SysVSharedMemoryComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();

        xServer.setSHMSegmentManager(new SHMSegmentManager(sysVSharedMemory));
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }

        sysVSharedMemory.deleteAll();
    }
}
