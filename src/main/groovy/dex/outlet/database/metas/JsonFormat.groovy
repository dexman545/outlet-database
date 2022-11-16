package dex.outlet.database.metas

import groovy.transform.ToString

@ToString
class JsonFormat {
    Date lastChanged
    ArrayList<McFabric> versions
}
