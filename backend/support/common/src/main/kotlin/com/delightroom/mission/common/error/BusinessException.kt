package com.delightroom.mission.common.error

/** 서비스 계층이 던지는 예외. 앱 모듈이 [ErrorCode.httpStatus] 로 응답을 만든다. */
open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.defaultMessage,
) : RuntimeException(message)
