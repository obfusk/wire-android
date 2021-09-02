#!/bin/bash
# FIXME: replace with properly published artefacts!
set -xe
wget -O artefacts.zip -- https://github.com/obfusk/wire-android/releases/download/foss-wip-artefacts/artefacts.zip
printf '0f4e913939d4ed4f7243eb38ddb9293091beccbd223693e3b38f58280eaa24032ffc47c89d88f4c630c4bc367c7ad6d0c2c103b793539453a285496d693de17c  artefacts.zip\n' | sha512sum -c
unzip artefacts.zip
cd artefacts
for artefact in *.aar *.jar; do
  pom="${artefact%.*}.pom"
  mvn install:install-file -Dfile="$artefact" -DpomFile="$pom"
done
cd ..
rm -fr artefacts.zip artefacts/
