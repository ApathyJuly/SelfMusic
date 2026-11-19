package com.selfmusic.server.media;

import lombok.Getter;

@Getter
public class InvalidRangeException extends RuntimeException {

    private final long size;

    public InvalidRangeException(long size) {
        super("请求的字节范围无效");
        this.size = size;
    }
}
