package com.delightroom.mission.app.api.auth

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * `@CurrentUser AuthPrincipal` 파라미터를 exchange 속성 [AuthPrincipal.ATTRIBUTE] 에서 꺼낸다.
 * 속성이 없으면(필터가 통과시키는 공개 경로에 붙인 경우) 401 `UNAUTHORIZED` — 익명으로 흘러가지 않게 한다.
 */
class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) &&
            AuthPrincipal::class.java.isAssignableFrom(parameter.parameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange,
    ): Mono<Any> =
        Mono.justOrEmpty<Any>(exchange.getAttribute<AuthPrincipal>(AuthPrincipal.ATTRIBUTE))
            .switchIfEmpty(Mono.error { BusinessException(ErrorCode.UNAUTHORIZED) })
}
