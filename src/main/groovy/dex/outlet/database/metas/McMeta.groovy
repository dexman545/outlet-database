package dex.outlet.database.metas

import groovy.transform.ToString

@ToString
class McMeta {
    private static def WIKI_URL = 'https://minecraft.wiki/w/' //Java_Edition_
    String id
    String url
    ReleaseType type

    String getWikiUrl() {
        WIKI_URL + id
    }
}
