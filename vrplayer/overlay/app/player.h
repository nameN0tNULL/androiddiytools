// AV1 software decoder backend for PICO OpenXR VideoPlayer demo.
#pragma once

#include <atomic>
#include <cstdint>
#include <list>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "oboe/Oboe.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

typedef enum {
    mediaTypeVideo = 0,
    mediaTypeAudio
} mediaType;

typedef struct MediaFrame_tag {
    MediaFrame_tag()
        : type(mediaTypeVideo), pts(0), width(0), height(0), number(0),
          data(nullptr), size(0), bufferIndex(-1) {}

    mediaType type;
    uint64_t pts;
    int32_t width;
    int32_t height;
    uint32_t number;
    uint8_t* data;
    uint32_t size;
    ssize_t bufferIndex;
    std::vector<uint8_t> owned;
} MediaFrame;

class CPlayer {
public:
    CPlayer();
    ~CPlayer();

    bool setDataSource(const char* source, int32_t& videoWidth, int32_t& videoHeight);
    bool start();
    bool stop();

    std::shared_ptr<MediaFrame> getFrame();
    bool releaseFrame(std::shared_ptr<MediaFrame>& frame);

private:
    bool openCodec(int streamIndex, AVCodecContext** out, bool preferDav1d);
    void decodeLoop();
    void decodeVideoPacket(AVPacket* packet, AVFrame* frame);
    void decodeAudioPacket(AVPacket* packet, AVFrame* frame);
    void queueVideoFrame(AVFrame* frame);
    bool ensureAudioResampler(AVFrame* frame);
    void closeAll();
    static uint64_t nowMs();

private:
    AVFormatContext* mFormat;
    AVCodecContext* mVideoCodec;
    AVCodecContext* mAudioCodec;
    SwsContext* mSws;
    SwrContext* mSwr;

    int mVideoStream;
    int mAudioStream;
    AVRational mVideoTimeBase;

    std::shared_ptr<oboe::AudioStream> mAudioStreamOut;

    std::atomic<bool> mStarted;
    std::atomic<bool> mStopRequested;
    std::thread mThread;

    uint64_t mPlaybackStartMs;
    int64_t mFirstVideoPtsMs;

    std::mutex mMediaListMutex;
    std::list<std::shared_ptr<MediaFrame>> mMediaList;
};
