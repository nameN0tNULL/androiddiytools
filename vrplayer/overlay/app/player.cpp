// FFmpeg/dav1d backend for PICO OpenXR VideoPlayer demo.
// Decodes video in software and produces NV12 frames expected by the
// existing OpenGLES 360 renderer.

#include "player.h"
#include "pch.h"
#include "common.h"

#include <algorithm>
#include <chrono>
#include <cstring>

extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libavutil/samplefmt.h>
}

CPlayer::CPlayer()
    : mFormat(nullptr),
      mVideoCodec(nullptr),
      mAudioCodec(nullptr),
      mSws(nullptr),
      mSwr(nullptr),
      mVideoStream(-1),
      mAudioStream(-1),
      mVideoTimeBase{1, 1000},
      mStarted(false),
      mStopRequested(false),
      mPlaybackStartMs(0),
      mFirstVideoPtsMs(-1) {
    avformat_network_init();
}

CPlayer::~CPlayer() {
    stop();
    closeAll();
}

uint64_t CPlayer::nowMs() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void CPlayer::closeAll() {
    if (mSwr) {
        swr_free(&mSwr);
    }
    if (mSws) {
        sws_freeContext(mSws);
        mSws = nullptr;
    }
    if (mVideoCodec) {
        avcodec_free_context(&mVideoCodec);
    }
    if (mAudioCodec) {
        avcodec_free_context(&mAudioCodec);
    }
    if (mFormat) {
        avformat_close_input(&mFormat);
    }
    mVideoStream = -1;
    mAudioStream = -1;
}

bool CPlayer::openCodec(int streamIndex, AVCodecContext** out, bool preferDav1d) {
    if (!mFormat || streamIndex < 0) return false;
    AVStream* stream = mFormat->streams[streamIndex];
    const AVCodecParameters* par = stream->codecpar;

    const AVCodec* decoder = nullptr;
    if (preferDav1d && par->codec_id == AV_CODEC_ID_AV1) {
        decoder = avcodec_find_decoder_by_name("libdav1d");
        if (decoder) {
            Log::Write(Log::Level::Info, "Using libdav1d for AV1 software decode");
        }
    }
    if (!decoder) decoder = avcodec_find_decoder(par->codec_id);
    if (!decoder) {
        Log::Write(Log::Level::Error, Fmt("No decoder for codec id %d", par->codec_id));
        return false;
    }

    AVCodecContext* ctx = avcodec_alloc_context3(decoder);
    if (!ctx) return false;
    if (avcodec_parameters_to_context(ctx, par) < 0) {
        avcodec_free_context(&ctx);
        return false;
    }

    ctx->thread_count = 0;
    if (avcodec_open2(ctx, decoder, nullptr) < 0) {
        avcodec_free_context(&ctx);
        return false;
    }

    *out = ctx;
    return true;
}

bool CPlayer::setDataSource(const char* source, int32_t& videoWidth, int32_t& videoHeight) {
    if (!source || !*source) return false;
    stop();
    closeAll();

    AVDictionary* openOpts = nullptr;
    av_dict_set(&openOpts, "rw_timeout", "15000000", 0);
    int ret = avformat_open_input(&mFormat, source, nullptr, &openOpts);
    av_dict_free(&openOpts);
    if (ret < 0) {
        Log::Write(Log::Level::Error, Fmt("avformat_open_input failed: %d", ret));
        return false;
    }

    if (avformat_find_stream_info(mFormat, nullptr) < 0) {
        Log::Write(Log::Level::Error, "avformat_find_stream_info failed");
        return false;
    }

    mVideoStream = av_find_best_stream(mFormat, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    mAudioStream = av_find_best_stream(mFormat, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (mVideoStream < 0) {
        Log::Write(Log::Level::Error, "No video stream");
        return false;
    }

    if (!openCodec(mVideoStream, &mVideoCodec, true)) return false;
    if (mAudioStream >= 0) {
        if (!openCodec(mAudioStream, &mAudioCodec, false)) {
            Log::Write(Log::Level::Warning, "Audio decoder unavailable; continuing without audio");
            mAudioStream = -1;
        }
    }

    mVideoTimeBase = mFormat->streams[mVideoStream]->time_base;
    videoWidth = mVideoCodec->width;
    videoHeight = mVideoCodec->height;

    Log::Write(Log::Level::Info,
               Fmt("FFmpeg source ready %dx%d videoStream=%d audioStream=%d",
                   videoWidth, videoHeight, mVideoStream, mAudioStream));
    return true;
}

bool CPlayer::start() {
    if (!mFormat || !mVideoCodec) return false;
    if (mStarted.exchange(true)) return true;

    mStopRequested = false;
    mPlaybackStartMs = nowMs();
    mFirstVideoPtsMs = -1;

    if (mAudioCodec) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Shared);
        builder.setFormat(oboe::AudioFormat::I16);
        builder.setChannelCount(2);
        builder.setSampleRate(48000);

        oboe::Result result = builder.openStream(mAudioStreamOut);
        if (result == oboe::Result::OK && mAudioStreamOut) {
            mAudioStreamOut->requestStart();
        } else {
            Log::Write(Log::Level::Warning, "Could not open Oboe audio stream");
            mAudioStreamOut.reset();
        }
    }

    mThread = std::thread(&CPlayer::decodeLoop, this);
    return true;
}

bool CPlayer::stop() {
    mStopRequested = true;
    if (mThread.joinable()) {
        mThread.join();
    }
    if (mAudioStreamOut) {
        mAudioStreamOut->requestStop();
        mAudioStreamOut->close();
        mAudioStreamOut.reset();
    }
    {
        std::lock_guard<std::mutex> guard(mMediaListMutex);
        mMediaList.clear();
    }
    mStarted = false;
    return true;
}

void CPlayer::decodeLoop() {
    AVPacket* packet = av_packet_alloc();
    AVFrame* videoFrame = av_frame_alloc();
    AVFrame* audioFrame = av_frame_alloc();

    while (!mStopRequested) {
        int ret = av_read_frame(mFormat, packet);
        if (ret < 0) break;

        if (packet->stream_index == mVideoStream) {
            decodeVideoPacket(packet, videoFrame);
        } else if (packet->stream_index == mAudioStream && mAudioCodec) {
            decodeAudioPacket(packet, audioFrame);
        }
        av_packet_unref(packet);
    }

    if (mVideoCodec) {
        avcodec_send_packet(mVideoCodec, nullptr);
        while (!mStopRequested && avcodec_receive_frame(mVideoCodec, videoFrame) == 0) {
            queueVideoFrame(videoFrame);
            av_frame_unref(videoFrame);
        }
    }

    av_frame_free(&videoFrame);
    av_frame_free(&audioFrame);
    av_packet_free(&packet);
    mStarted = false;
}

void CPlayer::decodeVideoPacket(AVPacket* packet, AVFrame* frame) {
    int ret = avcodec_send_packet(mVideoCodec, packet);
    if (ret < 0) return;

    while (!mStopRequested) {
        ret = avcodec_receive_frame(mVideoCodec, frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) break;
        queueVideoFrame(frame);
        av_frame_unref(frame);
    }
}

void CPlayer::queueVideoFrame(AVFrame* frame) {
    const int width = frame->width;
    const int height = frame->height;
    if (width <= 0 || height <= 0) return;

    while (!mStopRequested) {
        size_t queued = 0;
        {
            std::lock_guard<std::mutex> guard(mMediaListMutex);
            queued = mMediaList.size();
        }
        if (queued < 8) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (mStopRequested) return;

    std::shared_ptr<MediaFrame> out = std::make_shared<MediaFrame>();
    out->type = mediaTypeVideo;
    out->width = width;
    out->height = height;
    out->owned.resize((size_t)width * height * 3 / 2);
    uint8_t* dstY = out->owned.data();
    uint8_t* dstUV = dstY + (size_t)width * height;

    AVPixelFormat fmt = (AVPixelFormat)frame->format;
    if (fmt == AV_PIX_FMT_YUV420P || fmt == AV_PIX_FMT_YUVJ420P) {
        for (int y = 0; y < height; ++y) {
            memcpy(dstY + (size_t)y * width,
                   frame->data[0] + (size_t)y * frame->linesize[0], width);
        }
        for (int y = 0; y < height / 2; ++y) {
            const uint8_t* u = frame->data[1] + (size_t)y * frame->linesize[1];
            const uint8_t* v = frame->data[2] + (size_t)y * frame->linesize[2];
            uint8_t* uv = dstUV + (size_t)y * width;
            for (int x = 0; x < width / 2; ++x) {
                uv[x * 2] = u[x];
                uv[x * 2 + 1] = v[x];
            }
        }
    } else if (fmt == AV_PIX_FMT_NV12) {
        for (int y = 0; y < height; ++y) {
            memcpy(dstY + (size_t)y * width,
                   frame->data[0] + (size_t)y * frame->linesize[0], width);
        }
        for (int y = 0; y < height / 2; ++y) {
            memcpy(dstUV + (size_t)y * width,
                   frame->data[1] + (size_t)y * frame->linesize[1], width);
        }
    } else {
        mSws = sws_getCachedContext(
            mSws,
            width, height, fmt,
            width, height, AV_PIX_FMT_NV12,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        if (!mSws) return;

        uint8_t* dstData[4] = {dstY, dstUV, nullptr, nullptr};
        int dstLinesize[4] = {width, width, 0, 0};
        sws_scale(mSws, frame->data, frame->linesize, 0, height, dstData, dstLinesize);
    }

    out->data = out->owned.data();
    out->size = (uint32_t)out->owned.size();

    int64_t ts = frame->best_effort_timestamp;
    int64_t ptsMs = 0;
    if (ts != AV_NOPTS_VALUE) {
        ptsMs = (int64_t)(ts * av_q2d(mVideoTimeBase) * 1000.0);
    }
    if (mFirstVideoPtsMs < 0) mFirstVideoPtsMs = ptsMs;
    int64_t relative = std::max<int64_t>(0, ptsMs - mFirstVideoPtsMs);
    out->pts = mPlaybackStartMs + (uint64_t)relative;

    std::lock_guard<std::mutex> guard(mMediaListMutex);
    mMediaList.push_back(out);
}

bool CPlayer::ensureAudioResampler(AVFrame* frame) {
    if (mSwr) return true;

    AVChannelLayout outLayout = AV_CHANNEL_LAYOUT_STEREO;
    int ret = swr_alloc_set_opts2(
        &mSwr,
        &outLayout, AV_SAMPLE_FMT_S16, 48000,
        &frame->ch_layout, (AVSampleFormat)frame->format, frame->sample_rate,
        0, nullptr);
    if (ret < 0 || !mSwr) return false;
    return swr_init(mSwr) >= 0;
}

void CPlayer::decodeAudioPacket(AVPacket* packet, AVFrame* frame) {
    if (!mAudioCodec || !mAudioStreamOut) return;
    int ret = avcodec_send_packet(mAudioCodec, packet);
    if (ret < 0) return;

    while (!mStopRequested) {
        ret = avcodec_receive_frame(mAudioCodec, frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) break;

        if (ensureAudioResampler(frame)) {
            int outSamples = (int)av_rescale_rnd(
                swr_get_delay(mSwr, frame->sample_rate) + frame->nb_samples,
                48000, frame->sample_rate, AV_ROUND_UP);
            std::vector<int16_t> pcm((size_t)outSamples * 2);
            uint8_t* outData[1] = {reinterpret_cast<uint8_t*>(pcm.data())};
            int converted = swr_convert(
                mSwr, outData, outSamples,
                (const uint8_t**)frame->extended_data, frame->nb_samples);
            if (converted > 0) {
                mAudioStreamOut->write(pcm.data(), converted, 1000000000L);
            }
        }
        av_frame_unref(frame);
    }
}

std::shared_ptr<MediaFrame> CPlayer::getFrame() {
    std::lock_guard<std::mutex> guard(mMediaListMutex);
    if (mMediaList.empty()) return nullptr;
    return mMediaList.front();
}

bool CPlayer::releaseFrame(std::shared_ptr<MediaFrame>& frame) {
    if (!frame) return true;
    if (nowMs() < frame->pts) return false;

    std::lock_guard<std::mutex> guard(mMediaListMutex);
    if (!mMediaList.empty() && mMediaList.front() == frame) {
        mMediaList.pop_front();
    }
    return true;
}
