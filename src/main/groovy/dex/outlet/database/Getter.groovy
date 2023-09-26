package dex.outlet.database

import dex.outlet.database.metas.JsonFormat
import dex.outlet.database.metas.McFabric
import dex.outlet.database.metas.McMeta
import dex.outlet.database.metas.ReleaseType
import groovy.json.JsonOutput
import groovy.json.JsonParserType
import groovy.json.JsonSlurper
import net.fabricmc.loader.impl.game.minecraft.McVersion
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup
import org.codehaus.groovy.reflection.ReflectionUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

class Getter {
    final static def MC_META_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
    final static def McMetas = new LinkedHashSet<McMeta>()
    final static def VERSION_REGEX = Pattern.compile("(?:(?:Snapshot)|(?:Pre-release)|(?:Release candidate)) for (.+)")
    final static def LAST_TIME = ZonedDateTime.parse('2012-08-05T11:57:05+00:00', DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    //todo missing combat tests and some older versions may not be accurate
    static def update() {
        def f = new File('mc2fabric.json')
        f.createNewFile()
        def json = new JsonFormat(lastChanged: Date.from(ZonedDateTime.now().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toInstant()), versions: [])
        if (f.text != null && f.text != "") {
            json = new JsonSlurper(type: JsonParserType.INDEX_OVERLAY).parseText(f.text) as JsonFormat
        }

        def presentVersions = new HashSet<String>()
        def hasChange = false

        assert json.versions instanceof ArrayList

        json.versions.forEach { v ->
            presentVersions.add((v as McFabric).id)
        }

        McMetas.findAll {!presentVersions.contains(it.id) }.asList().reverseEach { m ->
            println 'Getting version for: ' + m.id
            json.versions.add(new McFabric(id: m.id, normalized: getFabricVersion(m), javaVersion: getJavaVersion(m), type: m.type))
            hasChange = true
        }

        if (hasChange) json.lastChanged = Date.from(ZonedDateTime.now().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toInstant())

        f.write(JsonOutput.prettyPrint(JsonOutput.toJson(json)).normalize())
    }

    static def getFabricVersion(McMeta meta) {
        McVersion.Builder builder = new McVersion.Builder()

        //todo error handling
        def converter = McVersionLookup.getDeclaredMethod("fromVersionJson", InputStream.class, McVersion.Builder.class)
        var maybe = ReflectionUtils.makeAccessible(converter)

        //todo add file fallback
        def stream = buildStreamFromWiki(meta)
        if (stream != null) {
            if (maybe.isPresent()) {
                def m = maybe.get() as Method
                if (m.invoke(null, stream, builder)) {
                    return builder.build().getNormalized()
                }
            }
        }
    }

    static def getJavaVersion(McMeta meta) {
        var launcherJson = new JsonSlurper().parse(new URL(meta.url))

        if (launcherJson instanceof Map) {
            if (launcherJson.containsKey('javaVersion')) {
                def jv = launcherJson.javaVersion
                if (jv instanceof Map) {
                    if (jv.containsKey('majorVersion')) {
                        def v = jv.majorVersion
                        if (v instanceof Integer) {
                            return (v ?: 8)
                        }
                    }
                }
            }
        }

        return 8
    }

    static def buildStreamFromWiki(McMeta meta) {
        try {
            Document doc = Jsoup.connect(meta.wikiUrl).get();
            Element table = doc.select(".infobox-rows").first();
            Iterator<Element> iterator = table.select("tr").iterator();
            while(iterator.hasNext()){
                var e = iterator.next()
                if (e.hasText()) {
                    var m = VERSION_REGEX.matcher(e.text())
                    if (m.find()) {

                        def out = """{"id":"${meta.id}", "release_target":"${m.group(1)}" }"""

                        return new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {

        }

        return new URL(meta.url).openStream()
    }

    static def getVersions() {
        println 'Querrying Mojang...'
        def mcVersions = new JsonSlurper().parse(new URL(MC_META_URL)) as LinkedHashMap

        if (mcVersions.containsKey("versions")) { //todo error handling
            mcVersions.versions.each { v ->
                assert v instanceof Map

                if (v.containsKey('releaseTime')) {
                    def date = ZonedDateTime.parse(v.releaseTime as String, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    if (date > LAST_TIME) {
                        if (v.containsKey("id") && v.containsKey("url") && v.containsKey("type")) {
                            McMetas.add(new McMeta(id: v.id, url: v.url, type: (v.type as String).toUpperCase() as ReleaseType))
                        } else {
                            throw new IllegalStateException("MC Launcher Json format changed!")
                        }
                    }
                } else {
                    throw new IllegalStateException("MC Launcher Json format changed the time!")
                }
            }
        }
    }
}
