package com.delightroom.mission.app.realtime.web

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

/** 스트림을 열기 전의 오류는 text/event-stream 이 아니라 JSON 으로 응답한다. */
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> = respond(e.errorCode, e.message)

    @ExceptionHandler(ServerWebInputException::class)
    fun handleInput(e: ServerWebInputException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage)

    private fun respond(code: ErrorCode, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(code.httpStatus)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(code.name, message))
}
