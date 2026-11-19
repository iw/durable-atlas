package com.example.fixture.helpers

import com.example.fixture.activities.InventoryActivities

/*
 * Ten-level helper chain. Only the last level calls an activity, so a caller that
 * inlines fewer than ten levels never sees it.
 */

fun deepChainLevel1(inventory: InventoryActivities, sku: String): String = deepChainLevel2(inventory, sku)

private fun deepChainLevel2(inventory: InventoryActivities, sku: String): String = deepChainLevel3(inventory, sku)

private fun deepChainLevel3(inventory: InventoryActivities, sku: String): String = deepChainLevel4(inventory, sku)

private fun deepChainLevel4(inventory: InventoryActivities, sku: String): String = deepChainLevel5(inventory, sku)

private fun deepChainLevel5(inventory: InventoryActivities, sku: String): String = deepChainLevel6(inventory, sku)

private fun deepChainLevel6(inventory: InventoryActivities, sku: String): String = deepChainLevel7(inventory, sku)

private fun deepChainLevel7(inventory: InventoryActivities, sku: String): String = deepChainLevel8(inventory, sku)

private fun deepChainLevel8(inventory: InventoryActivities, sku: String): String = deepChainLevel9(inventory, sku)

private fun deepChainLevel9(inventory: InventoryActivities, sku: String): String = deepChainLevel10(inventory, sku)

private fun deepChainLevel10(inventory: InventoryActivities, sku: String): String = inventory.reserveStock(sku, 1)
