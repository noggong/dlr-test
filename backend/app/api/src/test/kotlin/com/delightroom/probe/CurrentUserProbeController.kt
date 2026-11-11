package com.delightroom.probe

import com.delightroom.mission.app.api.auth.AuthPrincipal
import com.delightroom.mission.app.api.auth.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `@CurrentUser` 주입을 끝까지 확인하는 테스트 전용 컨트롤러. 앱 스캔 범위(`com.delightroom.mission`) 밖에 두어
 * `AccessTokenFilterTest` 가 `@Import` 할 때만 뜬다 — `RouteSmokeTest` 의 라우트 목록에 끼지 않는다.
 */
@RestController
class CurrentUserProbeController {
    @GetMapping(PATH)
    suspend fun me(@CurrentUser user: AuthPrincipal): Map<String, Any> =
        mapOf("userId" to user.userId.value, "loginId" to user.loginId.value, "groupId" to user.groupId.value)

    companion object {
        const val PATH = "/api/v1/test-probe/me"
    }
}
