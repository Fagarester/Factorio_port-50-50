package com.factorio.xserver;

public interface XLock extends AutoCloseable {
    @Override
    void close();
}
