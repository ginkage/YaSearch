package com.ginkage.yasearch;

import java.io.IOException;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

class TCPSocketFactory {
    TCPSocketFactory() {
    }

    public static Socket createSocket(TCPSocketFactory.SocketType socketType, String host, int port)
            throws IOException, TCPSocketFactory.BadSocketTypeException {
        Socket socket;
        switch (socketType) {
            case PLAIN_SOCKET:
                socket = new Socket(host, port);
                break;
            case SSL_SOCKET:
                SocketFactory socketFactory = SSLSocketFactory.getDefault();
                socket = socketFactory.createSocket(host, port);
                SSLSocket sslSocket = (SSLSocket)socket;
                HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
                sslSocket.setUseClientMode(true);
                sslSocket.startHandshake();
                SSLSession sslSession = sslSocket.getSession();
                if(!hostnameVerifier.verify(host, sslSession)) {
                    throw new SSLHandshakeException("Expected " + host
                            + "  found " + sslSession.getPeerPrincipal());
                }
                break;
            default:
                throw new TCPSocketFactory.BadSocketTypeException();
        }

        return socket;
    }

    public static class BadSocketTypeException extends Exception {
        public BadSocketTypeException() {
            super("Bad socket type");
        }
    }

    public enum SocketType {
        PLAIN_SOCKET,
        SSL_SOCKET
    }
}
