#!/bin/bash
# FIXME: replace with properly published artefacts!
set -xe
wget -O artefacts.zip -- https://github.com/obfusk/wire-android/releases/download/foss-wip-artefacts/artefacts.zip
printf '3345eeabf0ab4784cb46195957e1b778700e66c5b47de0ad8484ef3c426f913ee1425672fa0c8712755f03b6a58ed21715c6f3293708afae75a4751b5418543a  artefacts.zip\n' | sha512sum -c
unzip artefacts.zip
cd artefacts
for artefact in *.aar *.jar; do
  pom="${artefact%.*}.pom"
  mvn install:install-file -Dfile="$artefact" -DpomFile="$pom"
done
cd ..
rm -fr artefacts.zip artefacts/
