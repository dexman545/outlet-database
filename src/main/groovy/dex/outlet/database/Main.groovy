package dex.outlet.database

static void main(String[] args) {
  println "Updating!"

  if (args.contains("updateOld")) {
    var x = new Updater()
    x.update()
  } else {
    var x = new Getter()
    x.getVersions()
    x.update()
  }
}