package com.newcodes7.small_town.video.exception;

public class VideoNotFoundException extends RuntimeException {
    public VideoNotFoundException(Long videoId) {
        super("영상을 찾을 수 없습니다. ID: " + videoId);
    }
}
