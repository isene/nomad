package com.isene.watchit.data

import android.content.Context
import java.io.File
import uniffi.fe2o3_mobile_core.Details
import uniffi.fe2o3_mobile_core.ListItem
import uniffi.fe2o3_mobile_core.parseDetailsList
import uniffi.fe2o3_mobile_core.parseItems
import uniffi.fe2o3_mobile_core.serializeDetails
import uniffi.fe2o3_mobile_core.serializeItems

/** filesDir JSON persistence of the fetched catalog + details cache, so the
 *  app opens instantly without re-hitting TMDB. Serialization goes through the
 *  Rust core (same serde shape as desktop watchit's list.json/details.json). */
object Repo {
    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    fun loadItems(ctx: Context, name: String): List<ListItem> =
        file(ctx, name).takeIf { it.exists() }?.let { parseItems(it.readText()) } ?: emptyList()

    fun saveItems(ctx: Context, name: String, items: List<ListItem>) =
        file(ctx, name).writeText(serializeItems(items))

    fun loadDetails(ctx: Context): List<Details> =
        file(ctx, "details.json").takeIf { it.exists() }?.let { parseDetailsList(it.readText()) } ?: emptyList()

    fun saveDetails(ctx: Context, details: Collection<Details>) =
        file(ctx, "details.json").writeText(serializeDetails(details.toList()))
}
