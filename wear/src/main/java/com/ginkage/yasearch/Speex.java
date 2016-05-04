package com.ginkage.yasearch;

public class Speex
{
    /**
     * 8 KHz sample rate
     */
    public static final int NARROW_BAND = 0;

    /**
     * 16 KHz sample rate
     */
    public static final int WIDE_BAND = 1;

    /**
     * 32 KHz sample rate
     */
    public static final int ULTRA_WIDE_BAND = 2;

    /* quality
     * 1 : 4kbps (very noticeable artifacts, usually intelligible)
     * 2 : 6kbps (very noticeable artifacts, good intelligibility)
     * 4 : 8kbps (noticeable artifacts sometimes)
     * 6 : 11kpbs (artifacts usually only noticeable with headphones)
     * 8 : 15kbps (artifacts not usually noticeable)
     */

    static {
        System.loadLibrary("speex");
    }

    public native static int open(int band, int quality, byte[] header);
    public native static int getFrameSize();
    public native static int encode(short[] samples, byte[] output);
    public native static void close();

}