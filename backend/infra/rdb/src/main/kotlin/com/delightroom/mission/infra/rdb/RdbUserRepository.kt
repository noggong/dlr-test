package com.delightroom.mission.infra.rdb

import com.delightroom.mission.common.error.BusinessException
import com.delightroom.mission.common.error.ErrorCode
import com.delightroom.mission.core.domain.auth.Account
import com.delightroom.mission.core.domain.auth.SignedUp
import com.delightroom.mission.core.domain.group.GroupChoice
import com.delightroom.mission.core.domain.group.GroupId
import com.delightroom.mission.core.domain.group.GroupName
import com.delightroom.mission.core.domain.user.LoginId
import com.delightroom.mission.core.domain.user.User
import com.delightroom.mission.core.domain.user.UserId
import com.delightroom.mission.core.port.UserRepository
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * users · groups (LLD auth-signup §4 Q1 ~ Q6). 쿼리 id 는 LLD 표와 같다.
 *
 * UNIQUE 위반은 예외 문자열이 아니라 `ON CONFLICT DO NOTHING RETURNING` 의 0행으로 판정한다.
 */
internal class RdbUserRepository(
    private val db: DatabaseClient,
    private val tx: TransactionalOperator,
) : UserRepository {
    override suspend fun findById(id: UserId): User? = TODO("auth-signup")

    override suspend fun findByLoginId(loginId: LoginId): User? = TODO("auth-signup")

    override suspend fun save(user: User): User = TODO("auth-signup")

    override suspend fun signUp(
        loginId: LoginId,
        passwordHash: String,
        choice: GroupChoice,
        maxGroupSize: Int,
    ): SignedUp =
        // 한 트랜잭션(READ COMMITTED). BusinessException 을 던지면 그룹 생성 · 인원 증가까지 롤백된다(SPEC AS7)
        tx.executeAndAwait {
            val group = when (choice) {
                is GroupChoice.New -> createGroup(choice.name)
                is GroupChoice.Existing -> joinGroup(choice.name, maxGroupSize)
            }
            val joinedAt = insertUser(loginId, passwordHash, group.id)
                ?: throw BusinessException(ErrorCode.LOGIN_ID_TAKEN)
            SignedUp(loginId, group.name, joinedAt.toInstant())
        }

    /** Q1 — 비교 키가 겹치면(동시 생성 포함) 0행 → `GROUP_NAME_TAKEN`. */
    private suspend fun createGroup(name: GroupName): StoredGroup =
        db.sql(
            "INSERT INTO groups (name, name_key, member_count) VALUES (:name, :key, 1) " +
                "ON CONFLICT (name_key) DO NOTHING RETURNING id, name",
        )
            .bind("name", name.value)
            .bind("key", name.key)
            .map(::storedGroup)
            .awaitOneOrNull()
            ?: throw BusinessException(ErrorCode.GROUP_NAME_TAKEN)

    /**
     * Q2 가 0행이면 Q3 으로 「없음」과 「꽉 참」을 가른다. Q3 에 자리가 보이면 Q2 와 Q3 사이에 그 그룹이 막 생긴 것이라
     * Q2 를 한 번 더 한다 — 그룹은 지워지지 않으므로 또 0행이면 그사이 찬 것이다(LLD §3.2).
     */
    private suspend fun joinGroup(name: GroupName, maxGroupSize: Int): StoredGroup {
        incrementMemberCount(name, maxGroupSize)?.let { return it }
        val memberCount = memberCount(name) ?: throw BusinessException(ErrorCode.GROUP_NOT_FOUND)
        if (memberCount < maxGroupSize) {
            incrementMemberCount(name, maxGroupSize)?.let { return it }
        }
        throw BusinessException(ErrorCode.GROUP_FULL)
    }

    /** Q2 — 행 잠금 안의 조건부 증가. 마지막 자리에 둘이 오면 뒤의 쪽은 앞의 커밋 뒤 다시 조건을 보고 0행이 된다. */
    private suspend fun incrementMemberCount(name: GroupName, maxGroupSize: Int): StoredGroup? =
        db.sql(
            "UPDATE groups SET member_count = member_count + 1 " +
                "WHERE name_key = :key AND member_count < :max RETURNING id, name",
        )
            .bind("key", name.key)
            .bind("max", maxGroupSize)
            .map(::storedGroup)
            .awaitOneOrNull()

    /** Q3. */
    private suspend fun memberCount(name: GroupName): Int? =
        db.sql("SELECT member_count FROM groups WHERE name_key = :key")
            .bind("key", name.key)
            .map { row -> row.get("member_count", Number::class.java)!!.toInt() }
            .awaitOneOrNull()

    /** Q4 — 같은 아이디가 있으면(동시 가입 포함) 0행. `joined_at` 은 DB `now()`(SPEC AS15). */
    private suspend fun insertUser(loginId: LoginId, passwordHash: String, groupId: GroupId): OffsetDateTime? =
        db.sql(
            "INSERT INTO users (login_id, password_hash, group_id) VALUES (:loginId, :hash, :groupId) " +
                "ON CONFLICT (login_id) DO NOTHING RETURNING id, joined_at",
        )
            .bind("loginId", loginId.value)
            .bind("hash", passwordHash)
            .bind("groupId", groupId.value)
            .map { row -> row.get("joined_at", OffsetDateTime::class.java)!! }
            .awaitOneOrNull()

    override suspend fun findAccountByLoginId(loginId: LoginId): Account? =
        db.sql("$ACCOUNT_SELECT WHERE u.login_id = :loginId")
            .bind("loginId", loginId.value)
            .map(::account)
            .awaitOneOrNull()

    override suspend fun findAccountById(id: UserId): Account? =
        db.sql("$ACCOUNT_SELECT WHERE u.id = :userId")
            .bind("userId", id.value)
            .map(::account)
            .awaitOneOrNull()

    /** game-session Q5 — PK 순서로 한 번 훑는다(`joined_at` 단독 인덱스 없음). */
    override suspend fun findIdsJoinedIn(from: Instant?, to: Instant, after: UserId?, limit: Int): List<UserId> {
        require(limit > 0) { "limit 은 1 이상이다: $limit" }
        val lower = if (from == null) "" else "AND joined_at >= :from "
        var spec = db.sql("SELECT id FROM users WHERE id > :after ${lower}AND joined_at < :to ORDER BY id LIMIT :limit")
            .bind("after", after?.value ?: 0L)
            .bind("to", to.atOffset(ZoneOffset.UTC))
            .bind("limit", limit)
        if (from != null) spec = spec.bind("from", from.atOffset(ZoneOffset.UTC))
        return spec.map { row -> UserId(row.long("id")) }.flow().toList()
    }

    private class StoredGroup(val id: GroupId, val name: GroupName)

    private fun storedGroup(row: Readable): StoredGroup =
        StoredGroup(GroupId(row.long("id")), GroupName(row.string("name")))

    private fun account(row: Readable): Account =
        Account(
            userId = UserId(row.long("id")),
            loginId = LoginId(row.string("login_id")),
            passwordHash = row.string("password_hash"),
            groupId = GroupId(row.long("group_id")),
            groupName = GroupName(row.string("name")),
        )

    private fun Readable.long(name: String): Long = get(name, Long::class.javaObjectType)!!

    private fun Readable.string(name: String): String = get(name, String::class.java)!!

    private companion object {
        /** Q5 · Q6 공통. */
        const val ACCOUNT_SELECT =
            "SELECT u.id, u.login_id, u.password_hash, u.group_id, g.name " +
                "FROM users u JOIN groups g ON g.id = u.group_id"
    }
}
