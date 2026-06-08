package com.example.smartprice

import android.content.Context

class PriceDatabase(context: Context) {

    // Simple in-memory price database
    private val prices = mapOf(
        "beurre" to listOf(
            Pair("Carrefour", 5.25),
            Pair("Monoprix", 5.5),
            Pair("Géant", 5.9)
        ),
        "farine" to listOf(
            Pair("Carrefour", 1.5),
            Pair("Monoprix", 0.9),
            Pair("Géant", 1.2)
        ),
        "lait" to listOf(
            Pair("Carrefour", 1.5),
            Pair("Monoprix", 1.7),
            Pair("Géant", 1.3)
        )
    )

    /**
     * Get the cheapest price for a product
     * @param productName Name of the product (beurre, farine, lait)
     * @return Pair of (store name, price) or ("Not found", 0.0)
     */
    fun getCheapest(productName: String): Pair<String, Double> {
        val productPrices = prices[productName.lowercase()] ?: return Pair("Not found", 0.0)

        if (productPrices.isEmpty()) {
            return Pair("Not found", 0.0)
        }

        // Find minimum price
        return productPrices.minByOrNull { it.second } ?: Pair("Not found", 0.0)
    }

    /**
     * Get all prices for a product
     */
    fun getAllPrices(productName: String): List<Pair<String, Double>> {
        return prices[productName.lowercase()] ?: emptyList()
    }
}