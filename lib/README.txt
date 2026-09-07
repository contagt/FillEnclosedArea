This folder is where the Ant build (build.xml) looks for the plugin's two
dependency jars by default. Both need to be provided manually - the Ant
build does not fetch anything over the network.

1. josm-tested.jar (or josm-latest.jar / josm.jar)
   -----------------------------------------------
   This is the jar of the JOSM installation you want to compile and test
   against. You almost certainly already have it on disk somewhere - it's
   whatever jar your installed JOSM actually runs from. Typical locations:

     Linux (tarball/manual install): wherever you extracted it
     Linux (package manager):         /usr/share/josm/josm.jar (path varies)
     Windows:                         inside the JOSM installation folder
     macOS:                           inside JOSM.app/Contents/...

   If you don't have a local JOSM install, download the "tested" or
   "latest" jar directly from https://josm.openstreetmap.de/download/ .

   Either copy it into this lib/ folder as josm-tested.jar, or point the
   josm.jar property at wherever it already lives - see
   build.properties.sample in the project root.

2. jts-core-1.19.0.jar
   --------------------
   This plugin uses JTS's Polygonizer to detect enclosed faces in the
   existing way/node network - it's the library doing the actual "genuinely
   closed boundary, no gap bridging" geometry work.

   Download it (and its dependency, jts-io-common, is NOT needed - only
   jts-core is used here) from Maven Central, e.g.:

     https://repo1.maven.org/maven2/org/locationtech/jts/jts-core/1.19.0/jts-core-1.19.0.jar

   Place the downloaded jar in this lib/ folder under that exact name, or
   point the jts.jar property at wherever you put it.

Once both jars are in place (or referenced via build.properties), run:

    ant dist

from the project root.
