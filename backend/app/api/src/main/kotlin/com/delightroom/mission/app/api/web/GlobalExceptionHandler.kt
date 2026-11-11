package com.delightroom.mission.app.api.web

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.UnsupportedMediaTypeStatusException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> = respond(e.errorCode, e.message)

    /** 본문 없음 · JSON 아님 · 필드 빠짐 · enum 밖 값(디코딩 실패)과 JSON 이 아닌 `Content-Type` — 모두 400(LLD auth-signup §5). */
    @ExceptionHandler(ServerWebInputException::class, UnsupportedMediaTypeStatusException::class)
    fun handleInput(e: Exception): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage)

    private fun respond(code: ErrorCode, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(code.httpStatus)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(code.name, message))
}
