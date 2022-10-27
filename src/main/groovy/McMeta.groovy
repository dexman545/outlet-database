import groovy.transform.ToString

@ToString
class McMeta {
    private static def WIKI_URL = 'https://minecraft.fandom.com/wiki/Java_Edition_'
    String id
    String url

    String getWikiUrl() {
        WIKI_URL + id.replaceAll(" ", "_")
    }
}
