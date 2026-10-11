package sh.pug.android.sample

import android.app.Activity
import android.os.Bundle
import android.view.View
import sh.pug.android.Pug
import sh.pug.android.TrackOptions

class ProductActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product)
        if (savedInstanceState == null) {
            Pug.track.productViewed(
                productId = PRODUCT_ID,
                productName = "Trail runner",
                price = PRICE,
                currency = "USD",
            )
        }
        findViewById<View>(R.id.add_to_cart).setOnClickListener {
            Pug.track.addToCart(productId = PRODUCT_ID, price = PRICE, currency = "USD", quantity = 1)
        }
        findViewById<View>(R.id.buy_now).setOnClickListener {
            Pug.track.purchase(
                productId = PRODUCT_ID,
                amount = PRICE,
                currency = "USD",
                orderId = "order-${System.currentTimeMillis()}",
                extras = mapOf("giftWrap" to false),
                options = TrackOptions(immediate = true),
            )
        }
    }

    private companion object {
        const val PRODUCT_ID = "sku-1"
        const val PRICE = 89.0
    }
}
