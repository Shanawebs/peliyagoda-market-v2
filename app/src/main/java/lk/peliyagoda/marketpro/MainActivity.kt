package lk.peliyagoda.marketpro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.data.MarketRepository
import lk.peliyagoda.marketpro.ui.MarketProApp
import lk.peliyagoda.marketpro.ui.MarketViewModel

class MainActivity : ComponentActivity() {
    private val model: MarketViewModel by viewModels {
        MarketViewModel.factory(MarketRepository(MarketDatabase.get(applicationContext), applicationContext), applicationContext)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MarketProApp(model) }
    }
}
