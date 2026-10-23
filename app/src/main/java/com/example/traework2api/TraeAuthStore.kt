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

    /**
     * 只更新**指定账号**的 token（不改活跃账号、不写元数据）；账号不存在则忽略。
     *
     * 为什么不直接用 [save]：它会把保存的账号设为当前账号 —— 后台批量签到给非活跃账号
     * 续期时走它，用户的当前账号会被**偷偷切走**。
     */
    fun updateTokens(context: Context, cred: Cred) {
        if (readIndex(context).none { it.uid == cred.uid }) return
        prefs(context).edit()
            .putString(accessKey(cred.uid), encrypt(cred.accessToken))
            .putString(refreshKey(cred.uid), encrypt(cred.refreshToken))
            .apply()
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
    // 导出 / 导入（JSON 明文，换设备一键登录用）
    // ------------------------------------------------------------------

    /** 导出文件的格式标识，导入时用来确认"这是本 App 导出的账号表"。 */
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
        for (m in list(context).filter { uids == null || it.uid in uids }) {
            // 解不开的账号跳过，不导出半个条目（否则导入端只会得到一个空 token 的废号）
            val cred = loadByUid(context, m.uid) ?: continue
            arr.put(JSONObject()
                .put("uid", cred.uid)
                .put("nickname", cred.nickname)
                .put("region", cred.regionId)
                .put("expiresAt", cred.expiresAt)
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
     * 解析 + 校验（纯内存，任何非法条目跳过，不中断整批）。
     *
     * 抽出来给 [importJson] / [previewImport] 共用：两边解析口径分叉的话，
     * 预览答应"新增 2 个"、导入只进来 1 个，用户会以为丢账号。
     *
     * 这里**不读本地索引、不加密、不写盘**。
     *
     * @return 原始根对象（还要取 activeUid）+ uid→Cred（保持文件里的顺序）
     * @throws IllegalArgumentException 不是 JSON / 没有 accounts 数组 / 没有可导入条目
     */
    private fun parseAccounts(json: String): Pair<JSONObject, LinkedHashMap<String, Cred>> {
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

        val parsed = LinkedHashMap<String, Cred>()
        for (i in 0 until accounts.length()) {
            val o = accounts.optJSONObject(i) ?: continue
            val uid = o.optString("uid", "").trim()
            if (!uid.matches(UID_PATTERN)) continue
            val access = o.optString("accessToken", "")
            if (access.isBlank()) continue
            parsed[uid] = Cred(
                accessToken = access,
                refreshToken = o.optString("refreshToken", ""),
                expiresAt = o.optLong("expiresAt", 0L),
                uid = uid,
                nickname = o.optString("nickname", ""),
                // region 一律按 uid 长度重算，不采信文件（见 importJson 的历史注释）
                regionId = inferRegionFromUid(uid).id,
            )
        }
        if (parsed.isEmpty()) {
            throw IllegalArgumentException("JSON 里没有可导入的账号（缺 uid 或 accessToken）")
        }
        return root to parsed
    }

    /** 给文件中每条账号打标。只读索引，不碰 KeyStore —— UI 线程直接调。 */
    private fun classify(context: Context, parsed: Map<String, Cred>): List<ImportPreview> {
        val nowSec = System.currentTimeMillis() / 1000
        val local = readIndex(context).associateBy { it.uid }
        return parsed.values.map { cred ->
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

        // 分类 + **先把密文全部算出来**（encrypt 会抛，抛了此时还什么都没写）
        var added = 0
        var updated = 0
        var skipped = 0
        val toWrite = mutableListOf<Pair<Cred, Pair<String, String>>>()
        for (p in classify(context, parsed)) {
            when (p.status) {
                ImportStatus.SKIP -> {
                    skipped++
                    continue
                }
                ImportStatus.ADD -> added++
                ImportStatus.UPDATE -> updated++
            }
            val cred = parsed.getValue(p.uid)
            toWrite += cred to (encrypt(cred.accessToken) to encrypt(cred.refreshToken))
        }

        // 一次性落盘：全部密文 + 合并后的索引 + 当前账号
        val merged = readIndex(context).toMutableList()
        for ((cred, _) in toWrite) {
            val i = merged.indexOfFirst { it.uid == cred.uid }
            if (i >= 0) merged[i] = cred.toMeta() else merged.add(cred.toMeta())
        }
        // 一条都没写时别顺手改活跃账号：选的全是"跳过"、或用户取消了全部勾选
        val wantedActive = if (toWrite.isEmpty()) null else
            root.optString("activeUid", "").takeIf { it.isNotBlank() }
                ?.takeIf { uid -> merged.any { it.uid == uid } }
        val editor = prefs(context).edit()
        for ((cred, enc) in toWrite) {
            editor.putString(accessKey(cred.uid), enc.first)
            editor.putString(refreshKey(cred.uid), enc.second)
        }
        editor.putString(KEY_INDEX, toJson(merged).toString())
        wantedActive?.let { editor.putString(KEY_ACTIVE, it) }
        // commit 而不是 apply：用户点完就期待结果，必须拿到确定的成败
        if (!editor.commit()) throw IllegalStateException("写入失败（存储空间不足？）")

        // 区域跟上。不 sync 的话 trae_region 还停在导入前的值，转发会拿新号 token 打旧 host
        syncRegionToActiveAccount(context)
        return ImportReport(added, updated, skipped)
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

    /**
     * @Volatile：新增的 CreditMonitor 会在后台线程调 [list]，与 UI 线程并发的
     * "首读判定未迁移" 会让 [migrateLegacyIfNeeded] 跑两遍，而它是**整表覆盖** KEY_INDEX。
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

        // 导入合并规则（decideImportStatus）是**预览与导入共用**的判据：
        // 判错的方向不对称 —— 把"本地更新"判成覆盖，会把一个还能用的 refresh token 换成死值；
        // 把"本地已过期"判成跳过，用户导了却什么都没发生。
        val nowSec = 1_000L
        val fresh = AccountMeta("3695871032827027", "local-new", TraeRegion.CN.id, nowSec + 100)
        val stale = AccountMeta("3695871032827028", "local-old", TraeRegion.CN.id, nowSec - 100)
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
    }
}
