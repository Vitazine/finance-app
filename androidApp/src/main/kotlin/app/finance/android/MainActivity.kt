package app.finance.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.finance.android.ledger.LedgerViewModel

class MainActivity : ComponentActivity() {
    private val model by viewModels<LedgerViewModel> {
        viewModelFactory {
            initializer {
                val store = (this@MainActivity.application as FinanceApplication).ledgerStore
                LedgerViewModel(
                    store.settings,
                    store.categories,
                    store.transactions,
                    store.rates,
                    store.budgets,
                    store.analytics,
                    prepareCategories =
                    (this@MainActivity.application as FinanceApplication).categoryBootstrap::ensure
                )
            }
        }
    }
    private val ai by viewModels<app.finance.android.ai.AiViewModel> {
        viewModelFactory {
            initializer {
                app.finance.android.ai.AiViewModel(
                    (this@MainActivity.application as FinanceApplication).ledgerStore,
                    if (BuildConfig.DEBUG &&
                        BuildConfig.NATIVE_AI
                    ) {
                        app.finance.android.ai.AutoLocalModelEngine(application.filesDir, {
                            val info = android.app.ActivityManager.MemoryInfo()
                            application.getSystemService(
                                android.app.ActivityManager::class.java
                            ).getMemoryInfo(info)
                            info.totalMem
                        })
                    } else {
                        null
                    },
                    (
                        app.finance.ai.contract.LocalModelRef.create(
                            app.finance.android.ai.NativeInferenceEngine.KEY
                        ) as app.finance.ai.contract.AiResult.Success
                        ).value,
                    experimental = BuildConfig.DEBUG && BuildConfig.NATIVE_AI
                )
            }
        }
    }
    private val backup by viewModels<app.finance.android.backup.BackupViewModel> {
        viewModelFactory {
            initializer {
                val ledger = model
                val draft = ai
                app.finance.android.backup.BackupViewModel(
                    (this@MainActivity.application as FinanceApplication).ledgerStore.backups,
                    { !draft.state.value.busy && ledger.lockForImport() },
                    { replaced ->
                        if (replaced) draft.cancel()
                        ledger.importFinished(replaced)
                    },
                    beforeReplace = {
                        (this@MainActivity.application as FinanceApplication)
                            .categoryBootstrap.preserveImportedCatalog()
                    }
                )
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("appearance", MODE_PRIVATE) }
            var appearance by remember {
                mutableStateOf(
                    preferences.getString("theme", "system") ?: "system"
                )
            }
            val dark = when (appearance) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            FinanceTheme(darkTheme = dark) {
                FinanceApp(model, ai, backup, appearance) { chosen ->
                    if (chosen in setOf("system", "light", "dark")) {
                        preferences.edit { putString("theme", chosen) }
                        appearance = chosen
                    }
                }
            }
        }
    }
}
