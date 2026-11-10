package com.delightroom.mission.common.error

/** 서비스 계층이 던지는 예외. 앱 모듈이 [ErrorCode.httpStatus] 로 응답을 만든다. */
open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.defaultMessage,
) : RuntimeException(message)

/** 스켈레톤의 모든 미구현 지점이 던진다 → 501. [feature] 는 어느 기능 단위(prd §13)인지 적는다. */
class NotImplementedYet(feature: String) :
    BusinessException(ErrorCode.NOT_IMPLEMENTED, "${ErrorCode.NOT_IMPLEMENTED.defaultMessage} ($feature)")
