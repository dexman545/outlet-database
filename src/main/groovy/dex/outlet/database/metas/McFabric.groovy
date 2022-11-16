package dex.outlet.database.metas

import groovy.transform.ToString

@ToString
class McFabric {
    String id
    String normalized
    Integer javaVersion = 8
    ReleaseType type = ReleaseType.OLD_ALPHA
}
