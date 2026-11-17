package com.example.traework2api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Trae 登录凭证的**多账号**加密存取。
 *
 * 存储布局（全部在同一个 prefs 里）：
 *   trae_accounts              → 账号索引 JSON 数组（非敏感元数据：uid/nickname/region/expiresAt）
 *   trae_acc_<uid>_access      → access token 密文
 *   trae_acc_<uid>_refresh     → refresh token 密文
 *   trae_active_uid            → 当前选中账号的 uid
 *
 * 安全红线：token 只以密文落盘（AndroidKeyStore AES-GCM），索引里**绝不**出现 token。
 *
 * 兼容：旧版单账号的四条 key（trae_access/trae_refresh/trae_uid/trae_nick/trae_exp）
 * 会在首次读取时自动迁移成一个账号条目，用户不需要重新登录。
 */
object TraeAuthStore {

    private const val KEY_ALIAS = "trae_solo_aes_key"
    private const val PREF_NAME = "traework2api_prefs"
    private const val SEP = ":"   // 密文格式：base64(IV):base64(ciphertext+tag)

    private const val KEY_INDEX = "trae_accounts"   // JSON 数组
    private const val KEY_ACTIVE = "trae_active_uid"

    // 旧版单账号 key（仅用于迁移）
    private const val LEGACY_ACCESS = "trae_access"
    private const val LEGACY_REFRESH = "trae_refresh"
    private const val LEGACY_UID = "trae_uid"
    private const val LEGACY_NICK = "trae_nick"
    private const val LEGACY_EXP = "trae_exp"

    /** 单个账号的元数据（不含任何 token）。 */
    data class AccountMeta(
        val uid: String,
        val nickname: String,
        val regionId: String,
        val expiresAt: Long,      // unix 秒；0=未知
        /** 添加时间（unix 秒）。0 = 历史账号，未知 —— **不伪造**。 */
        val createdAt: Long = 0L,
        /** 最近一次「用户主动使用」的时间（unix 秒）。0 = 从未记录。 */
        val lastUsedAt: Long = 0L,
        /** 置顶：只影响显示顺序，不改数组位置。 */
        val pinned: Boolean = false,
    ) {
        val region: TraeRegion get() = TraeRegion.fromId(regionId)
    }

    /**
     * 存储写入结果。
     *
     * [PersistFailed] 与 [PersistenceLocked] 的区别：
     * - `PersistFailed`：本次真的尝试写了，`commit()` 返回 false。
     * - `PersistenceLocked`：StoreHealth 已是异常态，**本次根本没写**（闸门拦截）。
     */
    sealed class StoreResult {
        object Success : StoreResult()
        object NotFound : StoreResult()
        object PersistFailed : StoreResult()
        object CasConflict : StoreResult()
        object PersistenceLocked : StoreResult()
        /** 无需写盘（值未变），不算失败。 */
        object NoChange : StoreResult()
    }

    /** 存储健康状态。一旦异常，**本进程内不可逆**（防止"后一次成功=已恢复"的误判）。 */
    enum class StoreHealth { NORMAL, PERSISTENCE_UNCERTAIN }

    @Volatile
    private var health = StoreHealth.NORMAL

    @Volatile
    private var healthReason: String? = null

    /** 最近一次失败时间（用于警示条"新失败要重新出现"）。 */
    @Volatile
    private var lastFailureAt = 0L

    /** 用户最后一次关闭警示条的时间。仅内存，切页/重建不重置，进程重启归零。 */
    @Volatile
    private var bannerDismissedAt = 0L

    fun health(): StoreHealth = health

    fun healthReason(): String? = healthReason

    /** 警示条是否该显示：处于异常态，且失败发生在用户上次关闭之后。 */
    fun shouldShowHealthBanner(): Boolean =
        health != StoreHealth.NORMAL && lastFailureAt > bannerDismissedAt

    /** 关闭警示条：**只影响显示**，不清除健康状态、不解除写入闸门。 */
    fun dismissHealthBanner() {
        bannerDismissedAt = System.currentTimeMillis()
    }

    /** 必须在 indexLock 内调用。 */
    private fun markUncertainLocked(reason: String) {
        if (health == StoreHealth.NORMAL) {
            health = StoreHealth.PERSISTENCE_UNCERTAIN
            healthReason = reason
            android.util.Log.e("TraeAuthStore", "存储状态转为不确定：$reason")
        }
        lastFailureAt = System.currentTimeMillis()
    }

    /** 账号完整凭证（内存中才有 token）。 */
    data class Cred(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long, // unix 秒；0=未知
        val uid: String,
        val nickname: String,
        val regionId: String = TraeRegion.CN.id,
    ) {
        fun toMeta() = AccountMeta(uid, nickname, regionId, expiresAt)

        /** 该凭证所属区域（转发/续期都要用它选 host）。 */
        val region: TraeRegion get() = TraeRegion.fromId(regionId)
    }

    // ------------------------------------------------------------------
    // 账号列表 / 当前账号
    // ------------------------------------------------------------------

    /**
     * 全部账号元数据（按索引顺序）。
     *
     * **不再在这里做区域修补**：修补会写盘，而本方法被 UI 渲染链路大量调用，
     * 主线程同步落盘会掉帧。修补改到后台的 [loadActiveSession] / [loadFor] 里做。
     * 需要"列表 + 当前账号 + 健康状态"的一致视图时用 [snapshot]。
     */
    fun list(context: Context): List<AccountMeta> {
        migrateLegacyIfNeeded(context)
        return readIndex(context)
    }

    /** 当前选中的账号 uid；无账号返回 null。选择的账号已删时回退到第一个（只读，不写盘）。 */
    fun activeUid(context: Context): String? {
        val s = snapshot(context)
        return s.activeUid ?: s.suggestedActiveUid
    }

    /**
     * 账号列表的**一致快照**（只读，不写盘）。UI 与 Gateway 都应优先用它。
     *
     * 三个字段各司其职，**不要混用**：
     * - [keyActiveUid]：prefs 里**真实存的** active（可能是已删账号的 uid）。
     * - [suggestedActiveUid]：`keyActiveUid` 无效时建议改用的账号（首个）。
     * - [activeRepairPending]：需要修复 active，但**当前没有持久化**。
     *
     * UI 只能把 [activeUid]（= keyActiveUid 且仍在列表里）当作真实当前账号；
     * `suggestedActiveUid` 仅用于提示，**不能**据此把某行标记成"当前"，
     * 也**不能**据此把候选账号从可切换列表里排除。
     */
    data class Snapshot(
        val metas: List<AccountMeta>,
        val keyActiveUid: String?,
        val suggestedActiveUid: String?,
        val activeRepairPending: Boolean,
        val health: StoreHealth,
    ) {
        /** 真实当前账号：只有 keyActiveUid 仍存在于列表时才非空。 */
        val activeUid: String? get() = keyActiveUid?.takeIf { c -> metas.any { it.uid == c } }
        fun byUid(uid: String?): AccountMeta? = metas.firstOrNull { it.uid == uid }
    }

    fun snapshot(context: Context): Snapshot = synchronized(indexLock) {
        migrateLegacyIfNeeded(context)
        val metas = readIndex(context)
        val key = prefs(context).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
        val valid = key?.takeIf { c -> metas.any { it.uid == c } }
        Snapshot(
            metas = metas,
            keyActiveUid = key,
            suggestedActiveUid = if (valid == null) metas.firstOrNull()?.uid else null,
            activeRepairPending = valid == null && metas.isNotEmpty(),
            health = health,
        )
    }

    /** 锁内取「meta + 两个密文」。调用方必须在锁外解密。 */
    private data class CipherTriple(
        val meta: AccountMeta,
        val access: String,
        val refresh: String,
    )

    private fun cipherTripleLocked(context: Context, uid: String): CipherTriple? {
        val meta = readIndex(context).firstOrNull { it.uid == uid } ?: return null
        val p = prefs(context)
        val acc = p.getString(accessKey(uid), null)?.takeIf { it.isNotBlank() } ?: return null
        val ref = p.getString(refreshKey(uid), null).orEmpty()
        return CipherTriple(meta, acc, ref)
    }

    /** [loadActiveSession] 的结果。 */
    sealed class SessionResult {
        data class Success(val cred: Cred, val accessCipher: String, val refreshCipher: String) : SessionResult()
        object NotFound : SessionResult()
        object PersistFailed : SessionResult()
        object PersistenceLocked : SessionResult()
    }

    /**
     * 当前账号的一致会话（**仅后台线程**：active 无效时会写盘修复）。
     *
     * 锁内：读 active → 校验 → 无效则原子修复 → 取 meta + 双密文。
     * 锁外：解密两个密文。
     *
     * active 无效且存储已异常 → 返回 [SessionResult.PersistenceLocked]，
     * **不返回**未持久化的候选账号（否则上层会把它当成"已修复的当前账号"）。
     */
    fun loadActiveSession(context: Context): SessionResult {
        var triple: CipherTriple? = null
        var fail: SessionResult? = null
        synchronized(indexLock) {
            migrateLegacyIfNeeded(context)
            val metas = readIndex(context)
            if (metas.isEmpty()) { fail = SessionResult.NotFound; return@synchronized }
            val cur = prefs(context).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
            val valid = cur?.takeIf { c -> metas.any { it.uid == c } }
            val uid = if (valid != null) valid else {
                if (health != StoreHealth.NORMAL) { fail = SessionResult.PersistenceLocked; return@synchronized }
                val candidate = metas.first().uid
                if (!prefs(context).edit().putString(KEY_ACTIVE, candidate).commit()) {
                    markUncertainLocked("commit 失败（active 修复）")
                    fail = SessionResult.PersistFailed
                    return@synchronized
                }
                android.util.Log.i("TraeAuthStore", "active 指向无效账号，已修复为 $candidate")
                candidate
            }
            triple = cipherTripleLocked(context, uid)
            if (triple == null) fail = SessionResult.NotFound
        }
        fail?.let { return it }
        val t = triple ?: return SessionResult.NotFound
        val access = runCatching { decrypt(t.access) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return SessionResult.NotFound
        val refresh = runCatching { decrypt(t.refresh) }.getOrNull().orEmpty()
        return SessionResult.Success(
            Cred(access, refresh, t.meta.expiresAt, t.meta.uid, t.meta.nickname, t.meta.regionId),
            t.access, t.refresh,
        )
    }

    /**
     * 让**当前账号的区域**成为生效区域（兼容入口）。
     *
     * 旧实现是"锁外读 activeUid → 再读一次索引 → 写"：两次读之间账号被删就会写入
     * 已删账号的区。现在走 [snapshot] + [publishRegionIfActive]。
     */
    fun syncRegionToActiveAccount(context: Context) {
        val s = snapshot(context)
        val uid = s.activeUid ?: return
        val meta = s.byUid(uid) ?: return
        publishRegionIfActive(context, uid, meta.region)
    }

    /**
     * 只有 [uid] **仍是**当前账号时才发布区域。
     *
     * 锁内比对 active —— 旧线程拿到的过期 uid 不会覆盖新账号的区域。
     * 存储异常时只更新内存态（`TraeRegion.current`），**不写盘也不谎称已持久化**。
     */
    fun publishRegionIfActive(context: Context, uid: String, region: TraeRegion): Boolean =
        synchronized(indexLock) {
            if (prefs(context).getString(KEY_ACTIVE, null) != uid) return@synchronized false
            TraeRegion.current = region
            if (health == StoreHealth.NORMAL) {
                prefs(context).edit().putString("trae_region", region.id).apply()
            }
            true
        }

    /**
     * 设当前账号（**不动 lastUsedAt**）。内部用；用户主动切换请走 [switchTo]。
     *
     * 包在 indexLock 内：与 [remove] 互斥，否则可能把已删账号写成当前账号。
     */
    fun setActive(context: Context, uid: String): StoreResult = synchronized(indexLock) {
        if (health != StoreHealth.NORMAL) return@synchronized StoreResult.PersistenceLocked
        if (readIndex(context).none { it.uid == uid }) return@synchronized StoreResult.NotFound
        val ok = prefs(context).edit().putString(KEY_ACTIVE, uid).commit()
        if (!ok) { markUncertainLocked("commit 失败（setActive）"); StoreResult.PersistFailed } else StoreResult.Success
    }

    /**
     * 故障转移候选：**换一个别的账号**。
     * 优先选**受支持区域**的账号（国服）—— 国际服已冻结为未开发（聊天半残、额度极少），
     * 把它当兜底只会让用户从"国服 401"变成"国际服答不上来"，是更差的体验。
     * 只有确实没有受支持区域的账号时，才退而求其次换任何别的账号。
     * 只有一个账号时返回 null（没人可换，原错误照报）。
     */
    internal fun pickFailoverCandidate(metas: List<AccountMeta>, failedUid: String): AccountMeta? {
        val others = metas.filter { it.uid != failedUid }
        return others.firstOrNull { TraeRegion.fromId(it.regionId).isSupported } ?: others.firstOrNull()
    }

    /** 读取**当前账号**的凭证；无账号或解密失败返回 null。 */
    fun load(context: Context): Cred? = activeUid(context)?.let { loadByUid(context, it) }

    /** 读取指定账号的凭证；不存在或解密失败返回 null。锁内取密文、锁外解密。 */
    fun loadByUid(context: Context, uid: String): Cred? {
        val t = synchronized(indexLock) { cipherTripleLocked(context, uid) } ?: return null
        val access = runCatching { decrypt(t.access) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val refresh = runCatching { decrypt(t.refresh) }.getOrNull().orEmpty()
        return Cred(access, refresh, t.meta.expiresAt, t.meta.uid, t.meta.nickname, t.meta.regionId)
    }

    /**
     * 登录保存（**允许新增**）：token 密文 + 索引 + `KEY_ACTIVE` 一次 commit。
     *
     * 加密在锁外（KeyStore 慢），锁内只做合并与提交。
     * 语义保持：**登录成功即设为当前账号**（原 `save()` 的行为，未改动）。
     * 与并发的手动切换串行在同一把 indexLock 上，后完成者决定最终 active。
     */
    fun saveLogin(context: Context, cred: Cred): StoreResult {
        val encA = encrypt(cred.accessToken)
        val encR = encrypt(cred.refreshToken)
        val now = System.currentTimeMillis() / 1000
        return mutateIndex(
            context,
            extraKeys = mapOf(
                accessKey(cred.uid) to encA,
                refreshKey(cred.uid) to encR,
                KEY_ACTIVE to cred.uid,
            ),
        ) { metas ->
            val i = metas.indexOfFirst { it.uid == cred.uid }
            if (i >= 0) {
                // 重登 / 续期：只覆盖账号自身属性，**本地排序与使用记录全保留**
                metas[i] = metas[i].copy(
                    nickname = cred.nickname,
                    regionId = cred.regionId,
                    expiresAt = cred.expiresAt,
                )
            } else {
                metas.add(
                    AccountMeta(
                        uid = cred.uid, nickname = cred.nickname, regionId = cred.regionId,
                        expiresAt = cred.expiresAt, createdAt = now, lastUsedAt = now, pinned = false,
                    )
                )
            }
            true
        }
    }

    /**
     * 续期写回（**不允许新增**，带密文 CAS）。
     *
     * [expectAccess] / [expectRefresh] 是本次续期**开始前**读到的密文。提交时锁内比对，
     * 任一不一致即说明凭据已被别人换过（重新登录 / 另一个续期先提交 / 账号被删后重登），
     * 本次结果作废 —— 否则旧续期结果会覆盖刚登录的新 token。
     */
    fun saveRenewal(
        context: Context,
        cred: Cred,
        expectAccess: String,
        expectRefresh: String,
    ): StoreResult {
        val encA = encrypt(cred.accessToken)
        val encR = encrypt(cred.refreshToken)
        return synchronized(indexLock) {
            if (health != StoreHealth.NORMAL) return@synchronized StoreResult.PersistenceLocked
            val p = prefs(context)
            val curA = p.getString(accessKey(cred.uid), null)
            val curR = p.getString(refreshKey(cred.uid), null).orEmpty()
            if (curA == null) return@synchronized StoreResult.NotFound
            if (curA != expectAccess || curR != expectRefresh) {
                android.util.Log.i("TraeAuthStore", "续期结果作废（凭据已被替换）uid=${cred.uid}")
                return@synchronized StoreResult.CasConflict
            }
            val metas = readIndex(context).toMutableList()
            val i = metas.indexOfFirst { it.uid == cred.uid }
            if (i < 0) return@synchronized StoreResult.NotFound
            metas[i] = metas[i].copy(
                nickname = cred.nickname, regionId = cred.regionId, expiresAt = cred.expiresAt,
            )
            val ok = p.edit()
                .putString(accessKey(cred.uid), encA)
                .putString(refreshKey(cred.uid), encR)
                .putString(KEY_INDEX, toJson(metas).toString())
                .commit()
            if (!ok) { markUncertainLocked("commit 失败（续期写回）"); return@synchronized StoreResult.PersistFailed }
            StoreResult.Success
        }
    }

    /** 用户主动切换：验证存在 + 写 `KEY_ACTIVE` + 更新 `lastUsedAt`，同一锁区间。 */
    fun switchTo(context: Context, uid: String): StoreResult {
        val now = System.currentTimeMillis() / 1000
        var found = false
        val r = mutateIndex(context, extraKeys = mapOf(KEY_ACTIVE to uid)) { metas ->
            val i = metas.indexOfFirst { it.uid == uid }
            if (i < 0) return@mutateIndex null
            found = true
            metas[i] = metas[i].copy(lastUsedAt = now)
            true
        }
        return if (!found && r == StoreResult.NoChange) StoreResult.NotFound else r
    }

    /** 只更新 `lastUsedAt`（后台自动换号等场景**不要**调它）。 */
    fun markAccountUsed(context: Context, uid: String): StoreResult {
        val now = System.currentTimeMillis() / 1000
        var found = false
        val r = mutateIndex(context) { metas ->
            val i = metas.indexOfFirst { it.uid == uid }
            if (i < 0) return@mutateIndex null
            found = true
            metas[i] = metas[i].copy(lastUsedAt = now)
            true
        }
        return if (!found && r == StoreResult.NoChange) StoreResult.NotFound else r
    }

    fun setPinned(context: Context, uid: String, pinned: Boolean): StoreResult {
        var found = false
        val r = mutateIndex(context) { metas ->
            val i = metas.indexOfFirst { it.uid == uid }
            if (i < 0) return@mutateIndex null
            found = true
            metas[i] = metas[i].copy(pinned = pinned)
            true
        }
        return if (!found && r == StoreResult.NoChange) StoreResult.NotFound else r
    }

    fun renameAccount(context: Context, uid: String, nickname: String): StoreResult {
        val name = nickname.trim().take(32)
        var found = false
        val r = mutateIndex(context) { metas ->
            val i = metas.indexOfFirst { it.uid == uid }
            if (i < 0) return@mutateIndex null
            found = true
            metas[i] = metas[i].copy(nickname = name)
            true
        }
        return if (!found && r == StoreResult.NoChange) StoreResult.NotFound else r
    }

    /**
     * 在**可见序列**里上移/下移一位，然后交换两者在**底层数组**中的位置。
     *
     * 不能直接交换数组相邻项：当前账号从列表里隐藏，它可能正好卡在两个可见账号中间，
     * 交换数组相邻项会变成"和隐藏账号换位"，用户在界面上看不到任何变化。
     * 也不能跨 pinned 分组移动（显示顺序由 pinned 决定，跨组移动同样看不见效果）。
     *
     * @param delta -1 上移 / +1 下移
     * @param visibleUids 当前列表里**可见**的 uid（已按显示顺序）；null = 视为全部可见
     */
    fun moveAccount(context: Context, uid: String, delta: Int, visibleUids: List<String>?): StoreResult {
        var found = false
        var blocked = false
        val r = mutateIndex(context) { metas ->
            val selfIdx = metas.indexOfFirst { it.uid == uid }
            if (selfIdx < 0) return@mutateIndex null
            found = true
            val self = metas[selfIdx]
            // 可见序列（同 pinned 组）
            val group = (visibleUids ?: metas.map { it.uid })
                .filter { u -> metas.firstOrNull { it.uid == u }?.pinned == self.pinned }
            val pos = group.indexOf(uid)
            if (pos < 0 || pos + delta !in group.indices) { blocked = true; return@mutateIndex null }
            val targetUid = group[pos + delta]
            val targetIdx = metas.indexOfFirst { it.uid == targetUid }
            if (targetIdx < 0) { blocked = true; return@mutateIndex null }
            // 交换底层位置
            metas[selfIdx] = metas[targetIdx]
            metas[targetIdx] = self
            true
        }
        return when {
            !found && r == StoreResult.NoChange -> StoreResult.NotFound
            blocked -> StoreResult.NoChange
            else -> r
        }
    }

    /**
     * 只更新**指定账号**的 token（不改活跃账号、不写元数据）；账号不存在则忽略。
     *
     * 为什么不直接用 [saveLogin]：它会把保存的账号设为当前账号 —— 后台批量签到给
     * 非活跃账号续期时走它，用户的当前账号会被**偷偷切走**。
     */
    fun updateTokens(context: Context, cred: Cred): StoreResult {
        val encA = encrypt(cred.accessToken)
        val encR = encrypt(cred.refreshToken)
        return mutateIndex(
            context,
            extraKeys = mapOf(accessKey(cred.uid) to encA, refreshKey(cred.uid) to encR),
        ) { metas ->
            if (metas.none { it.uid == cred.uid }) null else false
        }
    }

    /** 只更新元数据（不改 token）；账号不存在返回 NotFound（**不会新增**）。 */
    fun updateMeta(context: Context, meta: AccountMeta): StoreResult = writeMeta(context, meta)

    /**
     * 删除账号（连密文一起删）。若删的是当前账号，自动切到剩下的第一个。
     *
     * token 删除、索引删除、`KEY_ACTIVE` 回退**同一次 commit** —— 否则会留下
     *「索引里没有但密文还在」或「active 指向已删账号」的半截状态。
     */
    fun remove(context: Context, uid: String): StoreResult = synchronized(indexLock) {
        if (health != StoreHealth.NORMAL) return@synchronized StoreResult.PersistenceLocked
        val metas = readIndex(context)
        if (metas.none { it.uid == uid }) return@synchronized StoreResult.NotFound
        val left = metas.filterNot { it.uid == uid }
        val p = prefs(context)
        val wasActive = p.getString(KEY_ACTIVE, null) == uid
        val ed = p.edit()
            .remove(accessKey(uid))
            .remove(refreshKey(uid))
            .putString(KEY_INDEX, toJson(left).toString())
        if (wasActive) ed.putString(KEY_ACTIVE, left.firstOrNull()?.uid.orEmpty())
        if (!ed.commit()) {
            markUncertainLocked("commit 失败（删除账号）")
            return@synchronized StoreResult.PersistFailed
        }
        StoreResult.Success
    }

    // ------------------------------------------------------------------
    // 导出 / 导入（JSON 明文，换设备一键登录用）
    // ------------------------------------------------------------------

    /**
     * 写进导出文件的格式标识，给人和外部工具看的。
     *
     * ⚠️ **导入并不校验它**（[parseAccounts] 只看 `accounts` 数组，连"顶层直接是数组"
     * 的裸格式都收）。这是有意的宽松：用户可能自己拼一份 JSON 导进来。
     * 所以别把这个常量当成"导入端的安全闸"——它只是一枚标记。
     */
    const val EXPORT_FORMAT = "traework2api-accounts"

    /** uid 会被直接拼进 prefs key（`trae_acc_<uid>_access`），必须收窄成纯数字。 */
    private val UID_PATTERN = Regex("^\\d{5,25}$")

    /**
     * 把选中的账号导出成 JSON 字符串。⚠️ 含**明文** access / refresh token。
     *
     * 为什么只能是明文：token 平时用 AndroidKeyStore（AES-GCM）加密落盘，而 KeyStore
     * 主密钥**绑死这台设备** —— 导出的密文换机后根本解不开。要"换设备不用重新登录"，
     * 导出的就只能是明文，导入方再用自己设备的 KeyStore 重新加密。
     * 因此导出内容等价于账号钥匙：只走本地文件，不联网、不写日志。
     *
     * 结构：
     * `{format, version, exportedAt, activeUid, accounts:[{uid,nickname,region,expiresAt,accessToken,refreshToken}]}`
     *
     * @param uids 只导出这些 uid（null = 全部）。解不开 token 的账号**仍会跳过**，
     *   所以文件里的实际条数可能少于 `uids.size` —— 用来起文件名时请用 [countAccounts]。
     */
    fun exportJson(context: Context, uids: Set<String>? = null): String {
        val arr = JSONArray()
        // 用 list() 的**原始数组顺序**导出（不排序）：自定义顺序就是数组顺序，
        // 排序后再导出会让"导出 → 导入"的顺序和用户看到的顺序不一致。
        for (m in list(context).filter { uids == null || it.uid in uids }) {
            // 解不开的账号跳过，不导出半个条目（否则导入端只会得到一个空 token 的废号）
            val cred = loadByUid(context, m.uid) ?: continue
            arr.put(JSONObject()
                .put("uid", cred.uid)
                .put("nickname", cred.nickname)
                .put("region", cred.regionId)
                .put("expiresAt", cred.expiresAt)
                // 排序相关：从 Meta 取（Cred 不含这些字段）
                .put("createdAt", m.createdAt)
                .put("lastUsedAt", m.lastUsedAt)
                .put("pinned", m.pinned)
                .put("accessToken", cred.accessToken)
                .put("refreshToken", cred.refreshToken))
        }
        // 只导子集时，当前账号很可能不在里面 —— 别写出一个文件里根本不存在的 activeUid
        val active = activeUid(context).orEmpty()
        return JSONObject()
            .put("format", EXPORT_FORMAT)
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis() / 1000)
            .put("activeUid", if (uids == null || active in uids) active else "")
            .put("accounts", arr)
            .toString(2)
    }

    /**
     * [json] 里实际有几个账号。
     *
     * 文件名要写真实条数而不是用户勾了几个：[exportJson] 会把解不开 token 的账号静默跳过，
     * 勾了 3 个只写出 2 条的文件如果叫 "3accounts"，导入端汇总数对不上，用户会以为文件坏了。
     */
    fun countAccounts(json: String): Int =
        runCatching { JSONObject(json).optJSONArray("accounts")?.length() ?: 0 }.getOrDefault(0)

    /** 导入结果，UI 原样报给用户（"新增 1，更新 2，跳过 1"）。 */
    data class ImportReport(val added: Int, val updated: Int, val skipped: Int) {
        val total: Int get() = added + updated
    }

    /** 导入时单个账号会走哪条路。 */
    enum class ImportStatus { ADD, UPDATE, SKIP }

    /**
     * 导入预览的一行。UI 只读它，**不自己重算合并规则**（规则在 [decideImportStatus]）。
     */
    data class ImportPreview(
        val uid: String,
        val nickname: String,
        val regionId: String,
        val status: ImportStatus,
        val fileExpiresAt: Long,  // unix 秒；0=未知
        val localExpiresAt: Long, // unix 秒；0=本地没有这个账号
    )

    /**
     * 合并规则的**唯一**判据 —— [importJson] 与 [previewImport] 都走它。
     *
     * 规则一旦分成两份，就会出现"预览说会更新、导入却跳过"，而用户完全没有办法判断哪个是对的。
     *
     * refresh token 是轮换的：本地那条明确没过期、而且比文件里的新时跳过，
     * 因为用旧备份覆盖等于把一个还能用的凭据换成死值（这个方向错一次就得重新扫码登录）。
     * 反过来本地那条已过期/未知（`expiresAt=0`）时照常覆盖 —— 它反正也用不了了。
     */
    internal fun decideImportStatus(old: AccountMeta?, fileExpiresAt: Long, nowSec: Long): ImportStatus =
        when {
            old != null && old.expiresAt > nowSec && old.expiresAt >= fileExpiresAt -> ImportStatus.SKIP
            old != null -> ImportStatus.UPDATE
            else -> ImportStatus.ADD
        }

    /**
     * 备份文件里的一条账号：凭证 + **排序相关的可选字段**。
     *
     * 用 `null` 表示"文件里没有这个字段"，与"文件里写了 0/false"区分开：
     * 前者要按导入规则兜底（createdAt=now / lastUsedAt=0 / pinned=false），后者要照搬。
     */
    private data class ImportEntry(
        val cred: Cred,
        val createdAt: Long?,
        val lastUsedAt: Long?,
        val pinned: Boolean?,
    )

    /**
     * 解析 + 校验（纯内存，任何非法条目跳过，不中断整批）。
     *
     * 抽出来给 [importJson] / [previewImport] 共用：两边解析口径分叉的话，
     * 预览答应"新增 2 个"、导入只进来 1 个，用户会以为丢账号。
     *
     * 这里**不读本地索引、不加密、不写盘**。
     *
     * @return 原始根对象（还要取 activeUid）+ uid→ImportEntry（保持文件里的顺序）
     * @throws IllegalArgumentException 不是 JSON / 没有 accounts 数组 / 没有可导入条目
     */
    private fun parseAccounts(json: String): Pair<JSONObject, LinkedHashMap<String, ImportEntry>> {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            // 兼容"顶层直接是数组"的裸格式
            val arr = try {
                JSONArray(json)
            } catch (_: Exception) {
                throw IllegalArgumentException("不是有效的账号 JSON")
            }
            JSONObject().put("accounts", arr)
        }
        val accounts = root.optJSONArray("accounts")
            ?: throw IllegalArgumentException("JSON 里没有 accounts 数组")

        val parsed = LinkedHashMap<String, ImportEntry>()
        for (i in 0 until accounts.length()) {
            val o = accounts.optJSONObject(i) ?: continue
            val uid = o.optString("uid", "").trim()
            if (!uid.matches(UID_PATTERN)) continue
            val access = o.optString("accessToken", "")
            if (access.isBlank()) continue
            val cred = Cred(
                accessToken = access,
                refreshToken = o.optString("refreshToken", ""),
                expiresAt = o.optLong("expiresAt", 0L),
                uid = uid,
                nickname = o.optString("nickname", ""),
                // region 一律按 uid 长度重算，不采信文件（见 importJson 的历史注释）
                regionId = inferRegionFromUid(uid).id,
            )
            parsed[uid] = ImportEntry(
                cred = cred,
                createdAt = if (o.has("createdAt")) o.optLong("createdAt", 0L) else null,
                lastUsedAt = if (o.has("lastUsedAt")) o.optLong("lastUsedAt", 0L) else null,
                pinned = if (o.has("pinned")) o.optBoolean("pinned", false) else null,
            )
        }
        if (parsed.isEmpty()) {
            throw IllegalArgumentException("JSON 里没有可导入的账号（缺 uid 或 accessToken）")
        }
        return root to parsed
    }

    /** 给文件中每条账号打标。只读索引，不碰 KeyStore —— UI 线程直接调。 */
    private fun classify(context: Context, parsed: Map<String, ImportEntry>): List<ImportPreview> {
        val nowSec = System.currentTimeMillis() / 1000
        val local = readIndex(context).associateBy { it.uid }
        return parsed.values.map { e ->
            val cred = e.cred
            val old = local[cred.uid]
            ImportPreview(
                uid = cred.uid,
                nickname = cred.nickname,
                regionId = cred.regionId,
                status = decideImportStatus(old, cred.expiresAt, nowSec),
                fileExpiresAt = cred.expiresAt,
                localExpiresAt = old?.expiresAt ?: 0L,
            )
        }
    }

    /**
     * 预览 [importJson] 会发生什么：不加密、不写盘、不改任何状态。
     * 用于导入前的确认弹窗（用户能看到"新增几个、跳过几个"，而不是导完才知道）。
     *
     * @throws IllegalArgumentException JSON 非法或没有可导入的条目
     */
    fun previewImport(context: Context, json: String): List<ImportPreview> =
        classify(context, parseAccounts(json).second)

    /**
     * 导入 [exportJson] 那种 JSON，按 uid **合并**（不做整表替换）。
     *
     * 事务纪律：**先全解析 + 全加密，最后一次 commit**。中途加密失败时 prefs 一个字节都不写 ——
     * 否则会留下"索引里有、token 没有"的账号（[loadByUid] 返回 null，既用不了也删不掉）。
     *
     * 合并规则见 [decideImportStatus]（与预览共用同一份判据）。
     * region 一律用 [inferRegionFromUid] 重算：`fromId` 对未知值静默回落 CN，
     * 而 [list] 每次都会按 uid 长度修补区域，采信文件只会造成前后不一致。
     *
     * @param onlyUids 只导入这些 uid（null = 文件里的全部）。**被剔除的账号不计入 skipped** ——
     *   skipped 的语义是"合并规则跳过了"，把用户没勾的算进去，"新增+更新+跳过"就和文件条数对不上了。
     * @throws IllegalArgumentException JSON 非法或没有可导入的条目
     */
    fun importJson(context: Context, json: String, onlyUids: Set<String>? = null): ImportReport {
        val (root, all) = parseAccounts(json)
        val parsed = if (onlyUids == null) all else
            LinkedHashMap(all.filterKeys { it in onlyUids })
        if (parsed.isEmpty()) return ImportReport(0, 0, 0)

        // ① 锁外：分类 + **先把密文全部算出来**（encrypt 会抛，抛了此时还什么都没写；
        //    KeyStore 慢，绝不能在 indexLock 内做）
        var added = 0
        var updated = 0
        var skipped = 0
        val toWrite = mutableListOf<Triple<ImportEntry, String, String>>()
        for (p in classify(context, parsed)) {
            when (p.status) {
                ImportStatus.SKIP -> { skipped++; continue }
                ImportStatus.ADD -> added++
                ImportStatus.UPDATE -> updated++
            }
            val entry = parsed.getValue(p.uid)
            toWrite += Triple(entry, encrypt(entry.cred.accessToken), encrypt(entry.cred.refreshToken))
        }
        if (toWrite.isEmpty()) return ImportReport(0, 0, skipped)

        // ② 锁内：合并 + active 决策 + 单次 commit
        var report: ImportReport? = null
        var error: String? = null
        synchronized(indexLock) {
            if (health != StoreHealth.NORMAL) {
                error = "存储状态异常，已暂停写入。请重启 App 后重试。"
                return@synchronized
            }
            val metas = readIndex(context).toMutableList()
            val now = System.currentTimeMillis() / 1000
            for ((entry, _, _) in toWrite) {
                val cred = entry.cred
                val i = metas.indexOfFirst { it.uid == cred.uid }
                if (i >= 0) {
                    // 已存在：只更新账号自身属性，**三字段与数组位置全部保留本地**
                    metas[i] = metas[i].copy(
                        nickname = cred.nickname, regionId = cred.regionId, expiresAt = cred.expiresAt,
                    )
                } else {
                    // 新账号：文件有就用文件值，没有才兜底（lastUsedAt 兜底为 0，导入不算"使用"）
                    metas.add(
                        AccountMeta(
                            uid = cred.uid, nickname = cred.nickname, regionId = cred.regionId,
                            expiresAt = cred.expiresAt,
                            createdAt = entry.createdAt ?: now,
                            lastUsedAt = entry.lastUsedAt ?: 0L,
                            pinned = entry.pinned ?: false,
                        )
                    )
                }
            }
            // active 决策**基于锁内最新状态**：已存在有效当前账号时保留用户选择，
            // 不允许用导入开始前的缓存值覆盖并发期间的手动切换。
            val curActive = prefs(context).getString(KEY_ACTIVE, null)
            val validCurrent = curActive?.takeIf { c -> metas.any { it.uid == c } }
            val finalActive = validCurrent ?: run {
                val fromFile = root.optString("activeUid", "")
                    .takeIf { it.isNotBlank() && metas.any { m -> m.uid == it } }
                fromFile ?: metas.firstOrNull()?.uid
            }
            val ed = prefs(context).edit()
            for ((entry, encA, encR) in toWrite) {
                ed.putString(accessKey(entry.cred.uid), encA)
                ed.putString(refreshKey(entry.cred.uid), encR)
            }
            ed.putString(KEY_INDEX, toJson(metas).toString())
            if (finalActive != null && finalActive != curActive) ed.putString(KEY_ACTIVE, finalActive)
            if (!ed.commit()) {
                markUncertainLocked("commit 失败（导入）")
                error = "写入失败（存储空间不足？）"
                return@synchronized
            }
            report = ImportReport(added, updated, skipped)
        }
        error?.let { throw IllegalStateException(it) }

        // ③ 锁外：区域跟上。不 sync 的话转发会拿新号 token 打旧 host
        syncRegionToActiveAccount(context)
        return report ?: ImportReport(0, 0, 0)
    }

    // ------------------------------------------------------------------
    // 索引读写
    // ------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun accessKey(uid: String) = "trae_acc_${uid}_access"
    private fun refreshKey(uid: String) = "trae_acc_${uid}_refresh"

    private fun readIndex(context: Context): List<AccountMeta> {
        val raw = prefs(context).getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uid = o.optString("uid", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AccountMeta(
                    uid = uid,
                    nickname = o.optString("nickname", ""),
                    regionId = o.optString("region", TraeRegion.CN.id),
                    expiresAt = o.optLong("expiresAt", 0L),
                    // 旧数据缺这三个字段 → 读成 0/false，行为与改造前完全一致（不伪造历史时间）
                    createdAt = o.optLong("createdAt", 0L),
                    lastUsedAt = o.optLong("lastUsedAt", 0L),
                    pinned = o.optBoolean("pinned", false),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 序列化索引。**必须写全 7 个字段**：
     * [repairMislabeledRegions] / [mutateIndex] 都是"读整个索引 → 写回"，漏写字段等于
     * 每次任意写操作都把 createdAt/lastUsedAt/pinned 抹掉（这是排序"随机失效"的根因）。
     */
    private fun toJson(metas: List<AccountMeta>): JSONArray = JSONArray().apply {
        metas.forEach { m ->
            put(JSONObject()
                .put("uid", m.uid)
                .put("nickname", m.nickname)
                .put("region", m.regionId)
                .put("expiresAt", m.expiresAt)
                .put("createdAt", m.createdAt)
                .put("lastUsedAt", m.lastUsedAt)
                .put("pinned", m.pinned))
        }
    }

    /**
     * 进程内索引锁。所有对 `trae_accounts` / `trae_acc_*_access|_refresh` /
     * `trae_active_uid` 的**写**都必须在这把锁内完成。
     *
     * 不引入 revision 乐观锁：单进程 App，一把锁足够，也没有"冲突了请重新加载"这种
     * 需要用户介入的失败态。
     */
    private val indexLock = Any()

    /**
     * **索引事务原语**：health 闸门 + 读-改-写 + 额外键，全在同一个 [indexLock] 区间。
     *
     * 三条铁律：
     * 1. `health` 检查是**锁内第一行** —— 不允许"检查完再进锁"，那中间就有竞争窗口。
     * 2. `commit()` 失败时**在同一锁区间**置 PERSISTENCE_UNCERTAIN，后续等待的写事务
     *    取得锁后立即被闸门拦下（返回 [StoreResult.PersistenceLocked]）。
     * 3. 加密、解密、网络、KeyStore **一律锁外**，这里只做内存 JSON 与一次提交。
     *
     * @param extraKeys 与索引同一次 commit 提交的键（token 密文 / `trae_active_uid`）。
     *   值为 null = 删除该键。传空 map 与传 null 等价。
     * @param block 修改 metas；返回 `true` = 有索引改动，`false` = 无改动（仍提交 extraKeys），
     *   `null` = **放弃整个事务**（索引与 extraKeys 都不写，用于"要操作的账号不存在"）。
     */
    private inline fun mutateIndex(
        context: Context,
        extraKeys: Map<String, String?> = emptyMap(),
        block: (MutableList<AccountMeta>) -> Boolean?,
    ): StoreResult = synchronized(indexLock) {
        if (health != StoreHealth.NORMAL) return@synchronized StoreResult.PersistenceLocked
        val metas = readIndex(context).toMutableList()
        val changed = block(metas) ?: return@synchronized StoreResult.NoChange
        if (!changed && extraKeys.isEmpty()) return@synchronized StoreResult.NoChange
        val ed = prefs(context).edit()
        if (changed) ed.putString(KEY_INDEX, toJson(metas).toString())
        for ((k, v) in extraKeys) if (v == null) ed.remove(k) else ed.putString(k, v)
        if (ed.commit()) StoreResult.Success else {
            markUncertainLocked("commit 失败（索引/凭据写入）")
            StoreResult.PersistFailed
        }
    }

    /**
     * 写入/替换一条元数据，保持其余条目与顺序。
     *
     * 已改为走 [mutateIndex]（锁内 + health 闸门 + commit 校验）。
     * 返回 [StoreResult.NotFound] 时**不会**凭空新增条目 —— 旧实现在"判断存在"与
     * "写回"之间有窗口，会把刚被删掉的账号重新 add 回来。
     */
    private fun writeMeta(context: Context, meta: AccountMeta): StoreResult {
        var found = false
        val r = mutateIndex(context) { metas ->
            val i = metas.indexOfFirst { it.uid == meta.uid }
            if (i < 0) return@mutateIndex false
            found = true
            metas[i] = meta
            true
        }
        return if (r == StoreResult.NoChange && !found) StoreResult.NotFound else r
    }

    // ------------------------------------------------------------------
    // 旧版单账号 → 多账号 迁移
    // ------------------------------------------------------------------

    /**
     * 旧数据迁移只跑一次的标志。
     *
     * ⚠️ 必须是 `synchronized` 块保护的 check-then-act，不能只靠 `@Volatile`：
     * 迁移是**整表覆盖** KEY_INDEX 的写，两个线程同时判"未迁移"会交错执行，
     * 可能吞掉并发 `save()` 刚写入的条目。锁对象用本 object 自身（TraeAuthStore
     * 的所有公开方法本就可能并发，这里串行化一次性的迁移足够）。
     */
    @Volatile
    private var migrated = false

    /**
     * 修补区域标注。
     *
     * 背景：迁移逻辑第一版把两个 uid 的长度记反了，已把国际服账号错标成 `cn`。
     * 这里按 uid 长度重算，不一致就改回来（幂等，改对了就不动）。
     * 只碰**区域**，不碰 token；区域错了会导致转发打到错误的 host，必须修。
     */
    /**
     * 修正区域标注（uid 位数 >= 19 = 国际服，否则国服）。
     *
     * 现在**只在后台**（[loadActiveSession] / [loadFor]）调用，不在 UI 渲染链路上：
     * 它会写盘，主线程同步落盘会掉帧。
     *
     * 存储已异常（PERSISTENCE_UNCERTAIN）时**只计算不写盘** —— 内存里能算出正确区域，
     * 但不能谎称已经持久化。返回是否"已写入磁盘"。
     */
    private fun repairMislabeledRegions(context: Context): Boolean = synchronized(indexLock) {
        val metas = readIndex(context)
        if (metas.isEmpty()) return@synchronized false
        var changed = false
        val fixed = metas.map { m ->
            val expect = inferRegionFromUid(m.uid).id
            if (m.regionId != expect) {
                android.util.Log.i("TraeAuthStore", "修正区域标注 uid=${m.uid}: ${m.regionId} → $expect")
                changed = true
                m.copy(regionId = expect)
            } else m
        }
        if (!changed) return@synchronized false
        if (health != StoreHealth.NORMAL) {
            android.util.Log.i("TraeAuthStore", "区域标注有误但存储异常，跳过写回")
            return@synchronized false
        }
        if (!prefs(context).edit().putString(KEY_INDEX, toJson(fixed).toString()).commit()) {
            markUncertainLocked("commit 失败（区域修补）")
            return@synchronized false
        }
        true
    }

    /**
     * 旧版数据没有区域字段。用 uid **长度**推断（实测样本，勿凭印象改）：
     *   国内服 `1234567890123456`       = 16 位
     *   国际服 `1234567890123456789`    = 19 位
     * 即**国际服更长**。≥19 位判国际服，否则国内服。
     * 认不出的一律按 CN（历史上只实测过这两类）。
     */
    internal fun inferRegionFromUid(uid: String): TraeRegion {
        val digits = uid.filter { it.isDigit() }
        return if (digits.length >= 19) TraeRegion.SG else TraeRegion.CN
    }

    /**
     * 把旧版单账号的四条 key 迁移成一个账号条目，然后删掉旧 key（避免明文/重复来源）。
     * 幂等：迁移过一次或没有旧数据时什么都不做。
     */
    private fun migrateLegacyIfNeeded(context: Context) {
        synchronized(this) {
            if (migrated) return
            migrated = true
            doMigrate(context)
        }
    }

    private fun doMigrate(context: Context) {
        val p = prefs(context)
        if (!p.contains(LEGACY_ACCESS)) return
        val access = p.getString(LEGACY_ACCESS, null)?.let { decrypt(it) }
        val uid = p.getString(LEGACY_UID, "").orEmpty()
        if (access.isNullOrBlank() || uid.isBlank()) {
            // 旧数据不完整：只清掉，避免留下半截状态
            p.edit().remove(LEGACY_ACCESS).remove(LEGACY_REFRESH)
                .remove(LEGACY_UID).remove(LEGACY_NICK).remove(LEGACY_EXP).apply()
            return
        }
        val refresh = p.getString(LEGACY_REFRESH, null)?.let { decrypt(it) }.orEmpty()
        val meta = AccountMeta(
            uid = uid,
            nickname = p.getString(LEGACY_NICK, "").orEmpty(),
            regionId = inferRegionFromUid(uid).id,
            expiresAt = p.getLong(LEGACY_EXP, 0L),
        )
        // 密文原样搬过去（重新加密一次更干净，但会多一次 KeyStore 调用）
        p.edit()
            .putString(accessKey(uid), encrypt(access))
            .putString(refreshKey(uid), encrypt(refresh))
            .putString(KEY_INDEX, toJson(listOf(meta)).toString())
            .putString(KEY_ACTIVE, uid)
            .remove(LEGACY_ACCESS).remove(LEGACY_REFRESH)
            .remove(LEGACY_UID).remove(LEGACY_NICK).remove(LEGACY_EXP)
            .apply()
        android.util.Log.i("TraeAuthStore", "已迁移旧版单账号 → 多账号 (uid=$uid)")
    }

    // ------------------------------------------------------------------
    // AES-GCM（KeyStore 主密钥）
    // ------------------------------------------------------------------

    /**
     * KeyStore 里的主密钥**缓存一份**。
     *
     * 为什么值得缓存：`KeyStore.getInstance().load(null)` + `getKey` 每次都是 Binder 往返，
     * 导入导出 N 个账号就是 2N 次初始化 —— 真机上肉眼可见的停顿（几十个号能卡住一秒以上）。
     * 主密钥在进程生命周期内不会变，缓存安全。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { cachedKey = it; return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey().also { cachedKey = it }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv, Base64.NO_WRAP) + SEP + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String? {
        return try {
            val parts = b64.split(SEP)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /**
     * 自检：索引 JSON 序列化/反序列化往返必须无损（uid 是主键，错了会串账号）。
     * 纯字符串逻辑，不碰 KeyStore，可在启动时安全调用。
     */
    fun selfCheck() {
        val src = listOf(
            AccountMeta("1234567890123456789", "intl-user", TraeRegion.SG.id, 1791341100L),
            AccountMeta("1234567890123456", "cn-user", TraeRegion.CN.id, 0L),
        )
        val back = try {
            val arr = toJson(src)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AccountMeta(
                    o.getString("uid"), o.getString("nickname"),
                    o.getString("region"), o.getLong("expiresAt"),
                )
            }
        } catch (e: Exception) {
            throw IllegalStateException("自检失败：账号索引序列化异常 ${e.message}")
        }
        check(back == src) { "自检失败：账号索引往返不一致" }
        // 区域解析必须按 id 命中，否则账号会挂到错的 host
        check(back[0].region == TraeRegion.SG) { "自检失败：国际服账号应解析为 SG" }
        check(back[1].region == TraeRegion.CN) { "自检失败：国内服账号应解析为 CN" }
        // 旧数据迁移靠 uid 长度推断区域，这里把实测样本钉住（16 位=CN，19 位=SG）
        check(inferRegionFromUid("1234567890123456") == TraeRegion.CN) { "自检失败：16 位 uid 应判为 CN" }
        check(inferRegionFromUid("1234567890123456789") == TraeRegion.SG) { "自检失败：19 位 uid 应判为 SG" }

        // 故障转移必须**优先选受支持区域**：国际服已冻结为未开发，
        // 拿它当兜底会把"国服 401"变成"国际服答不上来"，是更差的体验。
        val sg = AccountMeta("1234567890123456789", "intl", TraeRegion.SG.id, 0L)
        val cn = AccountMeta("1234567890123456", "cn", TraeRegion.CN.id, 0L)
        check(pickFailoverCandidate(listOf(sg, cn), sg.uid) == cn) {
            "自检失败：国际服挂掉时应换到国服账号"
        }
        check(pickFailoverCandidate(listOf(cn, sg), cn.uid) == sg) {
            "自检失败：只剩国际服可换时仍应给出它（聊胜于无）"
        }
        check(pickFailoverCandidate(listOf(cn), cn.uid) == null) {
            "自检失败：只有一个账号时不应换号"
        }
        // 两个国服账号 → 换到另一个国服（不要跳去国际服）
        val cn2 = AccountMeta("1234567890123457", "cn2", TraeRegion.CN.id, 0L)
        check(pickFailoverCandidate(listOf(cn, cn2, sg), cn.uid) == cn2) {
            "自检失败：有另一个国服账号时应优先换它，而不是国际服"
        }

        // 导入合并规则（decideImportStatus）是**预览与导入共用**的判据：
        // 判错的方向不对称 —— 把"本地更新"判成覆盖，会把一个还能用的 refresh token 换成死值；
        // 把"本地已过期"判成跳过，用户导了却什么都没发生。
        val nowSec = 1_000L
        val fresh = AccountMeta("1234567890123456", "local-new", TraeRegion.CN.id, nowSec + 100)
        val stale = AccountMeta("1234567890123457", "local-old", TraeRegion.CN.id, nowSec - 100)
        check(decideImportStatus(null, nowSec + 50, nowSec) == ImportStatus.ADD) {
            "自检失败：本地没有该账号应判新增"
        }
        check(decideImportStatus(fresh, nowSec + 50, nowSec) == ImportStatus.SKIP) {
            "自检失败：本地凭据更新且未过期应判跳过"
        }
        check(decideImportStatus(fresh, nowSec + 200, nowSec) == ImportStatus.UPDATE) {
            "自检失败：文件里的凭据更新时应判覆盖"
        }
        check(decideImportStatus(stale, nowSec + 50, nowSec) == ImportStatus.UPDATE) {
            "自检失败：本地凭据已过期时应判覆盖（旧备份不能把能用的号换死，但过期的必须能覆盖）"
        }
        check(decideImportStatus(stale, 0L, nowSec) == ImportStatus.UPDATE) {
            "自检失败：本地已过期时，文件里到期未知也应覆盖"
        }

        // —— 新增字段（排序/置顶）必须能被 copy 保留 ——
        // repairMislabeledRegions / setPinned / renameAccount 全是 `metas[i] = metas[i].copy(...)`，
        // 一旦 copy 丢字段，用户每次置顶/改名都会把排序数据抹掉（这类 bug 极难在界面上看出来）。
        run {
            val m = AccountMeta(
                uid = "u1", nickname = "n", regionId = TraeRegion.CN.id, expiresAt = 100L,
                createdAt = 200L, lastUsedAt = 300L, pinned = true,
            )
            val c = m.copy(regionId = TraeRegion.SG.id, nickname = "n2")
            check(c.createdAt == 200L && c.lastUsedAt == 300L && c.pinned) {
                "自检失败：copy 丢了 createdAt/lastUsedAt/pinned"
            }
            check(c.createdAt != 0L) { "自检失败：历史账号的 0 值不应被伪造成当前时间" }

            // 旧数据缺字段时必须读成 0/false（不伪造），行为与改造前一致
            val legacy = AccountMeta("u2", "old", TraeRegion.CN.id, 0L)
            check(legacy.createdAt == 0L && legacy.lastUsedAt == 0L && !legacy.pinned) {
                "自检失败：新增字段的默认值应为 0/0/false"
            }
        }

        // —— 排序稳定性：0 值之间保持原数组相对顺序（不靠时间戳瞎猜） ——
        run {
            val a = AccountMeta("a", "a", TraeRegion.CN.id, 0L, createdAt = 0L)
            val b = AccountMeta("b", "b", TraeRegion.CN.id, 0L, createdAt = 0L)
            val c = AccountMeta("c", "c", TraeRegion.CN.id, 0L, createdAt = 500L)
            val sorted = listOf(a, b, c).sortedByDescending { it.createdAt }
            check(sorted.map { it.uid } == listOf("c", "a", "b")) {
                "自检失败：createdAt 排序应把有值的排前、0 值保持原顺序，得到 ${sorted.map { it.uid }}"
            }
        }
    }
}
