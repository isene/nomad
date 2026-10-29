package com.isene.tasks.data

import uniffi.fe2o3_mobile_core.Hyperlist

// Flat row representation of the hyperlist for the LazyColumn. Lives in
// Kotlin (not the Rust core) because flattening depends on which
// categories are currently collapsed, which is purely a UI concern.

sealed class Row {
    data class Header(val catIdx: Int) : Row()
    data class Item(val catIdx: Int, val itemIdx: Int) : Row()
}

fun Hyperlist.flatRows(collapsed: Set<String>): List<Row> {
    val out = mutableListOf<Row>()
    categories.forEachIndexed { catIdx, c ->
        out.add(Row.Header(catIdx))
        if (!collapsed.contains(c.name)) {
            c.items.indices.forEach { itemIdx -> out.add(Row.Item(catIdx, itemIdx)) }
        }
    }
    return out
}
