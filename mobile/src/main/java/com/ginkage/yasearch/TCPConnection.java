package com.ginkage.yasearch;

import android.util.Log;

import com.ginkage.yasearch.TCPSocketFactory.SocketType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class TCPConnection {

    private static final String TAG = "TCPConnection";
    private static final int BUFFER_SIZE = 16384;
    private static final int ErrUnknown = -1;
    private static final int ErrEof = -2;

    private int mPort;
    private String mHost = "";
    private boolean mSsl;
    private final LinkedList<Packet> mWriteList = new LinkedList<>();
    private boolean mNeedToStop = false;
    private boolean mDoNotBeAfraid = false;
    private Socket mSocket;
    private Thread mReadThread;
    private Thread mWriteThread;
    private Lock mLock = new ReentrantLock();
    private Condition mHaveData;
    private Callback mCallback;

    public interface Callback {
        void onConnectionEstablished();
        void onDataReceived(byte[] data, int size);
        void onDataSent(long size);
        void onNetworkConnectionError(Exception e, int code);
    }

    private void call_onConnectionEstablished() {
        mCallback.onConnectionEstablished();
    }

    private void call_onDataReceived(byte[] data, int size) {
        mCallback.onDataReceived(data, size);
    }

    private void call_onDataSent(long size) {
        mCallback.onDataSent(size);
    }

    private void call_onNetworkConnectionError(Exception e, int code) {
        mCallback.onNetworkConnectionError(e, code);
    }

    public TCPConnection(String host, int port, boolean ssl, Callback callback) {
        mHaveData = mLock.newCondition();
        mPort = port;
        mHost = host;
        mSsl = ssl;
        mCallback = callback;
    }

    public void open() {
        (new Thread("TCPConnection.MainThread") {
            public void run() {
                try {
                    mSocket = TCPSocketFactory.createSocket(
                            (mSsl ? SocketType.SSL_SOCKET : SocketType.PLAIN_SOCKET), mHost, mPort);
                    mReadThread = new ReadThread(mSocket.getInputStream());
                    mReadThread.start();
                    mWriteThread = new WriteThread(mSocket.getOutputStream());
                    mWriteThread.start();
                } catch (IOException e) {
                    Log.d(TAG, "Socket opening error", e);
                    call_onNetworkConnectionError(e, ErrUnknown);
                    close();
                } catch (Exception e) {
                    call_onNetworkConnectionError(e, ErrUnknown);
                    close();
                }
            }
        }).start();
    }

    public void close() {
        Log.d(TAG, "TCPConnection.close()");
        mDoNotBeAfraid = true;
        mNeedToStop = true;
        (new Thread(new Runnable() {
            public void run() {
                mLock.lock();
                try {
                    if (mSocket != null) {
                        mSocket.close();
                    }
                    mHaveData.signal();
                } catch (IOException e) {
                    call_onNetworkConnectionError(e, ErrUnknown);
                }
                mLock.unlock();
            }
        })).start();
    }

    public void finish() {
        Log.d(TAG, "TCPConnection.finish()");
        synchronized (mWriteList) {
            mNeedToStop = true;
            mLock.lock();
            try {
                mHaveData.signal();
            } finally {
                mLock.unlock();
            }
        }
    }

    public void write(byte[] data) {
        synchronized (mWriteList) {
            mWriteList.addLast(new Packet(data, data.length));
            mLock.lock();
            try {
                mHaveData.signal();
            } finally {
                mLock.unlock();
            }
        }
    }

    private class ReadThread extends Thread {
        private final InputStream mStream;

        public ReadThread(InputStream stream) {
            super("TCPConnection.ReadThread");
            mStream = stream;
        }

        public void run() {
            Log.d(TAG, "TCPConnection.ReadThread.run()");

            try {
                int size;
                byte[] data = new byte[BUFFER_SIZE];
                while ((size = mStream.read(data)) != -1) {
                    call_onDataReceived(data, size);
                }

                Log.d(TAG, "TCPConnection.ReadThread.run(): EOF");
                call_onNetworkConnectionError(null, ErrEof);
            } catch (SocketException e) {
                if (!mDoNotBeAfraid) {
                    call_onNetworkConnectionError(e, ErrUnknown);
                    close();
                }
            } catch (IOException e) {
                call_onNetworkConnectionError(e, ErrUnknown);
                close();
            }

            Log.d(TAG, "TCPConnection.ReadThread.run() end");
        }
    }

    private class WriteThread extends Thread {
        private OutputStream mStream;

        public WriteThread(OutputStream stream) {
            super("TCPConnection.WriteThread");
            mStream = stream;
        }

        public void run() {
            Log.d(TAG, "TCPConnection.WriteThread.run()");
            call_onConnectionEstablished();

            try {
                while (true) {
                    try {
                        while (mWriteList.size() != 0) {
                            Packet data;
                            synchronized (mWriteList) {
                                if (mWriteList.size() == 0) {
                                    continue;
                                }
                                data = mWriteList.removeFirst();
                            }

                            mStream.write(data.getData());
                            call_onDataSent(data.getSize());
                        }

                        if (mNeedToStop) {
                            // call_onConnectionFinished(0);
                            break;
                        }

                        mLock.lock();
                        mHaveData.await();
                        mLock.unlock();
                    } catch (InterruptedException e) {
                        mLock.unlock();
                    }
                }
            } catch (SocketException e) {
                if (!mDoNotBeAfraid) {
                    call_onNetworkConnectionError(e, ErrUnknown);
                    close();
                }
            } catch (IOException e) {
                call_onNetworkConnectionError(e, ErrUnknown);
                close();
            }

            Log.d(TAG, "TCPConnection.WriteThread.run() end");
        }
    }

    class Packet {
        private final byte[] mData;
        private final Integer mSize;

        public Packet(byte[] data, int size) {
            mData = data;
            mSize = size;
        }

        public byte[] getData() {
            return mData;
        }

        public int getSize() {
            return mSize;
        }

        public int hashCode() {
            return Arrays.hashCode(mData) ^ mSize.hashCode();
        }

        public boolean equals(Object o) {
            if (o == null) {
                return false;
            } else if (!(o instanceof Packet)) {
                return false;
            } else {
                Packet packet = (Packet) o;
                return Arrays.equals(mData, packet.getData()) && mSize == packet.getSize();
            }
        }
    }

}
