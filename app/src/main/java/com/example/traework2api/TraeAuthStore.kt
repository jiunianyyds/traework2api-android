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
        val expiresAt: Long, // unix 秒；0=未知
    ) {
        val region: TraeRegion get() = TraeRegion.fromId(regionId)
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

    /** 全部账号元数据（按索引顺序）。会先做一次旧数据迁移 + 修补。 */
    fun list(context: Context): List<AccountMeta> {
        migrateLegacyIfNeeded(context)
        repairMislabeledRegions(context)
        return readIndex(context)
    }

    /** 当前选中的账号 uid；无账号返回 null。 */
    fun activeUid(context: Context): String? {
        migrateLegacyIfNeeded(context)
        repairMislabeledRegions(context)
        val p = prefs(context)
        val saved = p.getString(KEY_ACTIVE, null)
        val metas = readIndex(context)
        if (metas.isEmpty()) return null
        // 选中的账号可能已被删除 → 回退到第一个
        return saved?.takeIf { s -> metas.any { it.uid == s } } ?: metas.first().uid
    }

    /**
     * 让**当前账号的区域**成为生效区域。
     *
     * 账号自带的 region 才是权威——`trae_region` 只是"上次手动选的区域"，
     * 它可能和当前账号不一致（例：切到国际服账号后 prefs 仍是 cn），
     * 那样转发就会拿国际服 token 打国内服 host。启动与切账号时都应调用本方法。
     * 无账号时不动（保留用户手动选的区域，登录时还要用）。
     */
    fun syncRegionToActiveAccount(context: Context) {
        val uid = activeUid(context) ?: return
        val meta = readIndex(context).firstOrNull { it.uid == uid } ?: return
        TraeRegion.current = meta.region
        prefs(context).edit().putString("trae_region", meta.regionId).apply()
    }

    /** 切换当前账号。uid 不存在则忽略。 */
    fun setActive(context: Context, uid: String) {
        if (readIndex(context).none { it.uid == uid }) return
        prefs(context).edit().putString(KEY_ACTIVE, uid).apply()
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

    /** 读取指定账号的凭证；不存在或解密失败返回 null。 */
    fun loadByUid(context: Context, uid: String): Cred? {
        val p = prefs(context)
        val meta = readIndex(context).firstOrNull { it.uid == uid } ?: return null
        val access = p.getString(accessKey(uid), null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        val refresh = p.getString(refreshKey(uid), null)?.let { decrypt(it) }.orEmpty()
        return Cred(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = meta.expiresAt,
            uid = meta.uid,
            nickname = meta.nickname,
            regionId = meta.regionId,
        )
    }

    /**
     * 保存（新增或更新）一个账号并设为当前账号。
     * 用 uid 作为键，所以**同一账号重登不会产生重复条目**，也不会覆盖别的账号。
     */
    fun save(context: Context, cred: Cred) {
        val p = prefs(context)
        p.edit()
            .putString(accessKey(cred.uid), encrypt(cred.accessToken))
            .putString(refreshKey(cred.uid), encrypt(cred.refreshToken))
            .apply()
        writeMeta(context, cred.toMeta())
        setActive(context, cred.uid)
    }

    /** 只更新元数据（不改 token）；账号不存在则忽略。 */
    fun updateMeta(context: Context, meta: AccountMeta) {
        if (readIndex(context).none { it.uid == meta.uid }) return
        writeMeta(context, meta)
    }

    /** 删除账号（连密文一起删）。若删的是当前账号，自动切到剩下的第一个。 */
    fun remove(context: Context, uid: String) {
        val metas = readIndex(context).filterNot { it.uid == uid }
        val p = prefs(context)
        p.edit()
            .remove(accessKey(uid))
            .remove(refreshKey(uid))
            .putString(KEY_INDEX, toJson(metas).toString())
            .apply()
        if (p.getString(KEY_ACTIVE, null) == uid) {
            p.edit().putString(KEY_ACTIVE, metas.firstOrNull()?.uid.orEmpty()).apply()
        }
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
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun toJson(metas: List<AccountMeta>): JSONArray = JSONArray().apply {
        metas.forEach { m ->
            put(JSONObject()
                .put("uid", m.uid)
                .put("nickname", m.nickname)
                .put("region", m.regionId)
                .put("expiresAt", m.expiresAt))
        }
    }

    /** 写入/替换一条元数据，保持其余条目与顺序。 */
    private fun writeMeta(context: Context, meta: AccountMeta) {
        val metas = readIndex(context).toMutableList()
        val i = metas.indexOfFirst { it.uid == meta.uid }
        if (i >= 0) metas[i] = meta else metas.add(meta)
        prefs(context).edit().putString(KEY_INDEX, toJson(metas).toString()).apply()
    }

    // ------------------------------------------------------------------
    // 旧版单账号 → 多账号 迁移
    // ------------------------------------------------------------------

    private var migrated = false

    /**
     * 修补区域标注。
     *
     * 背景：迁移逻辑第一版把两个 uid 的长度记反了，已把国际服账号错标成 `cn`。
     * 这里按 uid 长度重算，不一致就改回来（幂等，改对了就不动）。
     * 只碰**区域**，不碰 token；区域错了会导致转发打到错误的 host，必须修。
     */
    private fun repairMislabeledRegions(context: Context) {
        val metas = readIndex(context)
        if (metas.isEmpty()) return
        var changed = false
        val fixed = metas.map { m ->
            val expect = inferRegionFromUid(m.uid).id
            if (m.regionId != expect) {
                android.util.Log.i("TraeAuthStore", "修正区域标注 uid=${m.uid}: ${m.regionId} → $expect")
                changed = true
                m.copy(regionId = expect)
            } else m
        }
        if (changed) prefs(context).edit().putString(KEY_INDEX, toJson(fixed).toString()).apply()
    }

    /**
     * 旧版数据没有区域字段。用 uid **长度**推断（实测样本，勿凭印象改）：
     *   国内服 `3695871032827027`       = 16 位
     *   国际服 `7687189514112844821`    = 19 位
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
        if (migrated) return
        migrated = true
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

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
        return gen.generateKey()
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
            AccountMeta("7687189514112844821", "intl-user", TraeRegion.SG.id, 1791341100L),
            AccountMeta("3695871032827027", "cn-user", TraeRegion.CN.id, 0L),
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
        check(inferRegionFromUid("3695871032827027") == TraeRegion.CN) { "自检失败：16 位 uid 应判为 CN" }
        check(inferRegionFromUid("7687189514112844821") == TraeRegion.SG) { "自检失败：19 位 uid 应判为 SG" }

        // 故障转移必须**优先选受支持区域**：国际服已冻结为未开发，
        // 拿它当兜底会把"国服 401"变成"国际服答不上来"，是更差的体验。
        val sg = AccountMeta("7687189514112844821", "intl", TraeRegion.SG.id, 0L)
        val cn = AccountMeta("3695871032827027", "cn", TraeRegion.CN.id, 0L)
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
        val cn2 = AccountMeta("3695871032827028", "cn2", TraeRegion.CN.id, 0L)
        check(pickFailoverCandidate(listOf(cn, cn2, sg), cn.uid) == cn2) {
            "自检失败：有另一个国服账号时应优先换它，而不是国际服"
        }
    }
}
