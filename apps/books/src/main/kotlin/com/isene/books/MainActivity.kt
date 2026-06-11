package com.isene.books

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.books.ui.BooksScreen
import com.isene.books.ui.theme.BooksTheme

class MainActivity : ComponentActivity() {
    private val vm: BooksViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BooksTheme { BooksScreen(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-scan on return — a freshly-grabbed book may have synced in.
        if (vm.open == null) vm.refresh()
    }
}
