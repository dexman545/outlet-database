package dex.outlet.database

static void main(String[] args) {
  println "Updating!"
  var x = new Getter()
  x.getVersions()
  x.update()
}