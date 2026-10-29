package com.sparkforge.reliefsync.ui.screens

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/** Tile source built from the server's /api/map config (same provider + key as the web app). */
class TemplateTileSource(private val template: String, subdomains: String, val dark: Boolean, name: String) :
    OnlineTileSourceBase(name, 0, 19, 256, "", arrayOf("https://tiles/")) {
    private val subs = subdomains.ifEmpty { "a" }
    override fun getTileURLString(index: Long): String {
        val z = MapTileIndex.getZoom(index); val x = MapTileIndex.getX(index); val y = MapTileIndex.getY(index)
        return template.replace("{s}", subs[(x + y) % subs.length].toString()).replace("{r}", "")
            .replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
    }
}
