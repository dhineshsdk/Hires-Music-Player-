package com.audiophile.engine.library.source;
public interface RemoteByteSource { long size(); int readAt(long offset, java.nio.ByteBuffer dst); void close(); }
