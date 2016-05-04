#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <speex/speex.h>
#include <speex/speex_header.h>
#include <ogg/ogg.h>
#include "include/ogg/ogg.h"

static int frame_size;
static SpeexBits bits;
static void *spxState;
static ogg_stream_state oggState;
static ogg_packet oggPacket;
static ogg_page oggPage;
static char compressedBits[200];
static int currentFrame;

int write_page(char *buffer, int offset) {
    ogg_stream_flush(&oggState, &oggPage);
    memcpy(buffer + offset, oggState.header, oggState.header_fill);
    offset += oggPage.header_len;
    memcpy(buffer + offset, oggState.body_data, oggState.body_fill);
    offset += oggPage.body_len;
    return offset;
}

extern "C"
JNIEXPORT jint JNICALL Java_com_ginkage_yasearch_Speex_open
        (JNIEnv *env, jclass type, jint band, jint quality, jbyteArray header) {
    const SpeexMode *mode;
    switch (band) {
        case 1:
            mode = &speex_wb_mode;
            break;
        case 2:
            mode = &speex_uwb_mode;
            break;
        default:
            mode = &speex_nb_mode;
            break;
    }

    currentFrame = 0;

    spxState = speex_encoder_init(mode);
    speex_encoder_ctl(spxState, SPEEX_SET_QUALITY, &quality);
    speex_encoder_ctl(spxState, SPEEX_GET_FRAME_SIZE, &frame_size);
    speex_bits_init(&bits);

    ogg_stream_init(&oggState, 0);

    // create speex header
    SpeexHeader spxHeader;
    speex_init_header(&spxHeader, 16000, 1, mode);

    // set audio and ogg packing parameters
    spxHeader.vbr = 0;
    spxHeader.bitrate = 16;
    spxHeader.frame_size = frame_size;
    spxHeader.frames_per_packet = 1;

    // wrap speex header in ogg packet
    int oggPacketSize;
    oggPacket.packet = (unsigned char *) speex_header_to_packet(&spxHeader, &oggPacketSize);
    oggPacket.bytes = oggPacketSize;
    oggPacket.b_o_s = 1; // Beginning of stream.
    oggPacket.e_o_s = 0;
    oggPacket.granulepos = 0;
    oggPacket.packetno = 0;
    ogg_stream_packetin(&oggState, &oggPacket);

    char *output = (char *)env->GetByteArrayElements(header, 0);
    int offset = write_page(output, 0);
    free(oggPacket.packet);

    env->ReleaseByteArrayElements(header, (jbyte *)output, 0);

    return (jint)offset;
}

static void throwIllegalArgumentException(JNIEnv *env, const char *msg) {
    jclass newExcCls = env->FindClass("java/lang/IllegalArgumentException");
    if (newExcCls == 0) /* Unable to find the new exception class, give up. */
	    return;
    env->ThrowNew(newExcCls, msg);
}

extern "C"
JNIEXPORT jint JNICALL Java_com_ginkage_yasearch_Speex_encode
        (JNIEnv *env, jclass type, jshortArray input_frame_, jbyteArray rval) {
    if (!spxState) {
        throwIllegalArgumentException(env,
                "Codec was not initialized before encoding");
        return NULL;
    }

    int nsamples = env->GetArrayLength(input_frame_);
    int i, nframes = nsamples / frame_size;
    if (nsamples != frame_size * nframes) {
        throwIllegalArgumentException(env,
                "Supplied data doesn't have integer number of frames");
        return NULL;
    }

    int noutput, written, offset = 0;
    short *input_frame = env->GetShortArrayElements(input_frame_, 0), *pin = input_frame;
    char *output_frame = (char *)env->GetByteArrayElements(rval, 0);
	for (i = 0; i < nframes; i++) {
        speex_bits_reset(&bits);
		speex_encode_int(spxState, pin, &bits);
        noutput = speex_bits_nbytes(&bits);
        written = speex_bits_write(&bits, compressedBits, noutput);

        oggPacket.packet = (unsigned char *)compressedBits;
        oggPacket.bytes = written;
        oggPacket.b_o_s = 0;
        oggPacket.e_o_s = 0;
        oggPacket.granulepos = (currentFrame + 1) * frame_size;
        oggPacket.packetno = oggState.packetno;
        ogg_stream_packetin(&oggState, &oggPacket);
        pin += frame_size;
	}
    offset = write_page(output_frame, offset);
    env->ReleaseShortArrayElements(input_frame_, input_frame, 0);
    env->ReleaseByteArrayElements(rval, (jbyte *)output_frame, 0);

    return (jint)offset;
}

extern "C"
JNIEXPORT jint JNICALL Java_com_ginkage_yasearch_Speex_getFrameSize(JNIEnv *env, jclass type) {
    if (!spxState)
        return 0;
    return frame_size;
}

extern "C"
JNIEXPORT void JNICALL Java_com_ginkage_yasearch_Speex_close(JNIEnv *env, jclass type) {
    if (!spxState)
        return;
    speex_bits_destroy(&bits);
    speex_encoder_destroy(spxState);
    spxState = NULL;
}
