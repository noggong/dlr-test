package com.delightroom.mission.core.domain.group

/** 가입할 때 고르는 그룹 — 새 그룹 만들기 또는 기존 그룹 들어가기(R-G1 · SPEC auth-signup AS4). */
sealed interface GroupChoice {
    val name: GroupName

    data class New(override val name: GroupName) : GroupChoice

    data class Existing(override val name: GroupName) : GroupChoice

    enum class Mode { NEW, EXISTING }

    companion object {
        fun of(mode: Mode, name: GroupName): GroupChoice =
            when (mode) {
                Mode.NEW -> New(name)
                Mode.EXISTING -> Existing(name)
            }
    }
}
