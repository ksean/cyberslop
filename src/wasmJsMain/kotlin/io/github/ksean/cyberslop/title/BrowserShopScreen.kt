package io.github.ksean.cyberslop.title

import io.github.ksean.cyberslop.progression.UpgradeId
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

internal fun renderShopScreen(
    root: HTMLElement,
    state: ShopScreenState,
    onPurchase: (UpgradeId, expectedRank: Int) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
) {
    root.textContent = ""
    root.className = "shop-screen"
    root.appendChild(element("h1", "Shop"))
    root.appendChild(element("p", "Available Scrap: ${state.spendableScrap}", "shop-balance"))

    state.rows.forEach { row ->
        val panel = element("section", className = "shop-upgrade")
        panel.appendChild(element("h2", row.name))
        panel.appendChild(element("p", row.description))
        panel.appendChild(element("p", row.rankText, "shop-rank"))
        panel.appendChild(element("p", row.effectText, "shop-effect"))
        panel.appendChild(element("p", row.priceText, "shop-price"))
        panel.appendChild(
            button(row.purchaseAccessibleName, disabled = !row.canPurchase) {
                onPurchase(row.id, row.rank)
            },
        )
        root.appendChild(panel)
    }

    root.appendChild(button("Back", onClick = onBack))
}

private fun element(tag: String, text: String = "", className: String = ""): HTMLElement =
    (document.createElement(tag) as HTMLElement).apply {
        textContent = text
        this.className = className
    }

private fun button(
    name: String,
    disabled: Boolean = false,
    onClick: () -> Unit,
): HTMLButtonElement = (document.createElement("button") as HTMLButtonElement).apply {
    type = "button"
    textContent = name
    this.disabled = disabled
    onclick = { _ -> onClick() }
}
