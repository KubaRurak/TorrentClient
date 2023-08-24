package com.torrentclient;

public interface ClientExceptionCallback {
    void onException(Client client, Exception e);
}
