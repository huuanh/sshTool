package com.example.termiusclone.backup

import com.example.termiusclone.data.db.AppDb
import com.example.termiusclone.data.db.HostEntity
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.data.db.SnippetEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON backup with `_v` for forward-compat. Known hosts are NOT exported
 * (they are derived from `Connect once`). Passwords/keys ARE exported as-is.
 */
object BackupManager {

    private const val VERSION = 1

    suspend fun export(db: AppDb): String {
        val root = JSONObject()
        root.put("_v", VERSION)
        val hosts = JSONArray()
        for (h in db.hosts().allOnce()) hosts.put(hostToJson(h))
        val ids = JSONArray()
        for (i in db.identities().all()) ids.put(identityToJson(i))
        val snips = JSONArray()
        for (s in db.snippets().all()) snips.put(snippetToJson(s))
        root.put("hosts", hosts)
        root.put("identities", ids)
        root.put("snippets", snips)
        return root.toString(2)
    }

    /** Returns counts (hosts, identities, snippets) imported. */
    suspend fun import(db: AppDb, json: String): Triple<Int, Int, Int> {
        val root = JSONObject(json)
        val hostArr = root.optJSONArray("hosts") ?: JSONArray()
        val idArr = root.optJSONArray("identities") ?: JSONArray()
        val snipArr = root.optJSONArray("snippets") ?: JSONArray()

        // Map old-id -> new-id for identities so hosts can be re-pointed.
        val idMap = mutableMapOf<Long, Long>()
        for (i in 0 until idArr.length()) {
            val o = idArr.getJSONObject(i)
            val oldId = o.optLong("id", 0L)
            val newId = db.identities().insert(jsonToIdentity(o, newId = 0L))
            idMap[oldId] = newId
        }
        for (i in 0 until hostArr.length()) {
            val o = hostArr.getJSONObject(i)
            val mappedIdent = o.optLong("identity_id", 0L).takeIf { it != 0L }?.let { idMap[it] }
            db.hosts().insert(jsonToHost(o, newId = 0L, mappedIdentityId = mappedIdent))
        }
        for (i in 0 until snipArr.length()) {
            val o = snipArr.getJSONObject(i)
            db.snippets().insert(jsonToSnippet(o, newId = 0L))
        }
        return Triple(hostArr.length(), idArr.length(), snipArr.length())
    }

    private fun hostToJson(h: HostEntity) = JSONObject().apply {
        put("id", h.id)
        put("alias", h.alias)
        put("hostname", h.hostname)
        put("port", h.port)
        put("username", h.username)
        put("password", h.password ?: JSONObject.NULL)
        put("identity_id", h.identityId ?: JSONObject.NULL)
        put("port_forwards", h.portForwards ?: JSONObject.NULL)
    }

    private fun jsonToHost(o: JSONObject, newId: Long, mappedIdentityId: Long?) = HostEntity(
        id = newId,
        alias = o.optString("alias", ""),
        hostname = o.optString("hostname"),
        port = o.optInt("port", 22),
        username = o.optString("username"),
        password = o.optStringOrNull("password"),
        identityId = mappedIdentityId,
        portForwards = o.optStringOrNull("port_forwards")
    )

    private fun identityToJson(i: IdentityEntity) = JSONObject().apply {
        put("id", i.id)
        put("name", i.name)
        put("username", i.username)
        put("password", i.password ?: JSONObject.NULL)
        put("private_key", i.privateKey ?: JSONObject.NULL)
        put("passphrase", i.passphrase ?: JSONObject.NULL)
        put("public_key", i.publicKey ?: JSONObject.NULL)
    }

    private fun jsonToIdentity(o: JSONObject, newId: Long) = IdentityEntity(
        id = newId,
        name = o.optString("name"),
        username = o.optString("username"),
        password = o.optStringOrNull("password"),
        privateKey = o.optStringOrNull("private_key"),
        passphrase = o.optStringOrNull("passphrase"),
        publicKey = o.optStringOrNull("public_key")
    )

    private fun snippetToJson(s: SnippetEntity) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("command", s.command)
    }

    private fun jsonToSnippet(o: JSONObject, newId: Long) = SnippetEntity(
        id = newId,
        name = o.optString("name"),
        command = o.optString("command")
    )

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val s = optString(key, "")
        return if (s.isEmpty()) null else s
    }
}
