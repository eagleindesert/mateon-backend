package com.example.mateon.common.exception;

import lombok.Getter;

@Getter
public class MateonException extends RuntimeException {
    private final ErrorCode errorCode;

    public MateonException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public MateonException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

