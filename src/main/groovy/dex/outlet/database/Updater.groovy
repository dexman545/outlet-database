package dex.outlet.database

import dex.outlet.database.metas.JsonFormat
import dex.outlet.database.metas.McFabric
import dex.outlet.database.metas.McMeta
import groovy.json.JsonOutput
import groovy.json.JsonParserType
import groovy.json.JsonSlurper
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class Updater {
    static def update() {
        def f = new File('mc2fabric.json')
        f.createNewFile()
        def json = new JsonFormat(lastChanged: Date.from(ZonedDateTime.now().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toInstant()), versions: [])
        if (f.text != null && f.text != "") {
            json = new JsonSlurper(type: JsonParserType.INDEX_OVERLAY).parseText(f.text) as JsonFormat
        }

        def presentVersions = new ArrayList<McFabric>()
        def hasChange = false

        assert json.versions instanceof ArrayList

        json.versions.each { v ->
            def n2 = McVersionLookup.normalizeVersion(v.id, McVersionLookup.getRelease(v.id))
            if (v.normalized != n2) {
                v = new McFabric(id: v.id, normalized: n2, javaVersion: v.javaVersion, type: v.type)
                hasChange = true
            }
            presentVersions.add(v)
        }

        json.versions = presentVersions

        if (hasChange) json.lastChanged = Date.from(ZonedDateTime.now().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toInstant())

        f.write(JsonOutput.prettyPrint(JsonOutput.toJson(json)).normalize())
    }
}
